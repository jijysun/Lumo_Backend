package Lumo.lumo_backend.domain.member.service;

import Lumo.lumo_backend.domain.alarm.entity.MissionHistory;
import Lumo.lumo_backend.domain.alarm.entity.repository.MissionHistoryRepository;
import Lumo.lumo_backend.domain.email.service.EmailService;
import Lumo.lumo_backend.domain.member.dto.MemberReqDTO;
import Lumo.lumo_backend.domain.member.dto.MemberRespDTO;
import Lumo.lumo_backend.domain.member.dto.MissionStat;
import Lumo.lumo_backend.domain.member.entity.memberEnum.Login;
import Lumo.lumo_backend.domain.member.entity.Member;
import Lumo.lumo_backend.domain.member.entity.memberEnum.MemberRole;
import Lumo.lumo_backend.domain.member.exception.MemberException;
import Lumo.lumo_backend.domain.member.repository.MemberRepository;
import Lumo.lumo_backend.domain.member.status.MemberErrorCode;
import Lumo.lumo_backend.global.security.jwt.JWT;
import Lumo.lumo_backend.global.security.jwt.JWTProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
@EnableAsync
public class MemberService {

    private final MemberRepository memberRepository;
    private final MissionHistoryRepository missionHistoryRepository;
    private final EmailService emailService;
    private final RedisTemplate redisTemplate;
    private final JWTProvider jwtProvider;
    private final BCryptPasswordEncoder encoder;

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 4;

    /** 이메일 인증 성공 티켓. signIn / changePassword 가 1회 소비한다 (C-2). */
    private static final String VERIFIED_KEY_PREFIX = "verified:";
    private static final Duration VERIFIED_TTL = Duration.ofMinutes(10);


    public MemberRespDTO.GetLoginDTO getLogin(Member member) {

        if (member == null) {
            throw new MemberException(MemberErrorCode.CANT_FOUND_MEMBER);
        }

        Optional<Member> byEmail = memberRepository.findByEmail(member.getEmail());
        if (byEmail.isPresent()) {
            return MemberRespDTO.GetLoginDTO.builder().login(byEmail.get().getLogin()).build();
        } else {
            return MemberRespDTO.GetLoginDTO.builder().login(Login.NULL).build();
        }
    }

    @Transactional(readOnly = true)
    public Boolean checkEmailDuplicate(String email) {
        Optional<Member> byEmail = memberRepository.findByEmail(email);
        if (byEmail.isPresent()) {
//            log.info("[MemberService - checkEmailDuplicate] duplicate email {}", email);
            throw new MemberException(MemberErrorCode.EXIST_MEMBER);
        } else {
//            log.info("[MemberService - checkEmailDuplicate] Success to check duplicate {}", email);
            return true;
        }
    }

    public void requestVerificationCode(String email) {
        String code = generateVerificationCode();
        Boolean ifAbsent = redisTemplate.opsForValue().setIfAbsent(email, code, Duration.ofMinutes(3));

        if (Boolean.FALSE.equals(ifAbsent)){
//            log.info("[MemberService - requestVerificationCode] already send to {} with {}", email, redisTemplate.opsForValue().get(email));
            throw new MemberException(MemberErrorCode.ALREADY_SEND); // 따닥 방지
        }
        else{
            redisTemplate.opsForList().leftPush("email_queue", email + ":" + code);
//            log.info("[MemberService - requestVerificationCode] call EmailService with {} - {}", email, code);
//            emailService.startWork();
        }
    }
    public String generateVerificationCode() {
        Random random = new Random();
        StringBuilder code = new StringBuilder(CODE_LENGTH);

        for (int i = 0; i < CODE_LENGTH; i++) {
            int randomIndex = random.nextInt(CHARACTERS.length());

            code.append(CHARACTERS.charAt(randomIndex));
        }

        return code.toString();
    }


    public void verifyCode(String email, String code) {
        String savedCode = (String) redisTemplate.opsForValue().get(email);

        if (savedCode == null) {
            throw new MemberException(MemberErrorCode.WRONG_CODE);
        } else if (!savedCode.equals(code)) {
            throw new MemberException(MemberErrorCode.WRONG_CODE);
        }

        // 검증에 쓴 코드는 즉시 폐기한다.
        // 남겨두면 3분 TTL 이 끝날 때까지 같은 코드로 무제한 재검증이 가능했다.
        redisTemplate.delete(email);

        // 인증에 성공했다는 사실을 티켓으로 남긴다. signIn / changePassword 가 이 티켓을 소비한다.
        // 이전에는 성공 사실을 어디에도 저장하지 않아 verify-code 를 건너뛰고 바로 signIn 을 호출해도 가입이 됐다.
        redisTemplate.opsForValue().set(VERIFIED_KEY_PREFIX + email, "true", VERIFIED_TTL);
    }

    /**
     * 이메일 인증 티켓을 소비한다(1회용).
     *
     * <p>Redis {@code DEL} 은 "키가 있었으면 1, 없었으면 0"을 반환하므로 확인과 소비가 한 번의 원자적
     * 커맨드로 끝난다. get→delete 로 나누면 두 요청이 같은 티켓을 동시에 통과할 수 있다.
     */
    private void consumeVerification(String email) {
        Boolean consumed = redisTemplate.delete(VERIFIED_KEY_PREFIX + email);

        if (!Boolean.TRUE.equals(consumed)) {
            throw new MemberException(MemberErrorCode.EMAIL_NOT_VERIFIED);
        }
    }

    public void signIn(MemberReqDTO.SignInRequestDTO dto) {
        // 이메일 인증을 통과한 요청만 가입시킨다 (C-2).
        consumeVerification(dto.getEmail());

        Optional<Member> byEmail = memberRepository.findByEmail(dto.getEmail());

        if (byEmail.isPresent()) {
            throw new MemberException(MemberErrorCode.EXIST_MEMBER);
        }

        memberRepository.save(Member.create(dto.getEmail(), dto.getUsername(), encoder.encode(dto.getPassword()), Login.NORMAL, MemberRole.USER));

//        log.info("[MemberService - signIn] Success to signIn -> {}, {}", dto.getEmail(), dto.getUsername());
    }

    public MemberRespDTO.MemberInfoDTO login(MemberReqDTO.LoginReqDTO dto) {
        Member member = memberRepository.findByEmail(dto.getEmail()).orElseThrow(() -> new MemberException(MemberErrorCode.CANT_FOUND_MEMBER));
        if (!encoder.matches(dto.getPassword(), member.getPassword())) {
            throw new MemberException(MemberErrorCode.CANT_FOUND_MEMBER);
        }

        List<SimpleGrantedAuthority> authorities = Collections.singletonList(new SimpleGrantedAuthority(member.getRole().toString()));
        Authentication authentication = new UsernamePasswordAuthenticationToken(member.getEmail(), member.getPassword(), authorities);
        JWT jwt = jwtProvider.generateToken(authentication);

        // TTL 은 RT 자체의 exp 에서 역산한다 (H-2).
        // TTL 없이 set 하면 로그아웃하지 않은 사용자의 키가 영구히 남아 회원 수에 비례해 단조 증가한다.
        // 상수를 복제하지 않고 토큰의 만료 시각을 쓰므로, C-3 로 RT 수명을 늘려도 TTL 이 자동으로 따라간다.
        redisTemplate.opsForValue().set(
                "refresh:" + dto.getEmail(),
                jwt.getRefreshToken(),
                jwtProvider.getRemainingTime(jwt.getRefreshToken()),
                TimeUnit.MILLISECONDS);

//        log.info("[MemberService - login] Success to login -> {} - {}", dto.getEmail(), jwt.getRefreshToken());

        return MemberRespDTO.MemberInfoDTO.builder().jwt(jwt).username(member.getUsername()).build();
    }

    public void logout (String accessToken, Long memberId){
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new MemberException(MemberErrorCode.CANT_FOUND_MEMBER));
        redisTemplate.delete("refresh:"+member.getEmail());

        Long remainingTime = jwtProvider.getRemainingTime(accessToken); // TTL 용 남은 시간 계산

        if (remainingTime > 0) {
            String key = "blacklist:" + accessToken;
            redisTemplate.opsForValue().set(key, "logout", remainingTime, TimeUnit.MILLISECONDS);
            log.info("[MemberService] - add AccessToken in BlackList! remainingTime: {}ms", remainingTime);
        }

        log.info("[MemberService - logout] Success to logout -> {}", member.getEmail());
    }

    public MemberRespDTO.FindEmailRespDTO findEmail(String email) {
        boolean existsByEmail = memberRepository.existsByEmail(email);

        if (existsByEmail) {
            return MemberRespDTO.FindEmailRespDTO.builder().email(email).build();
        }
        else{
            throw new MemberException(MemberErrorCode.CANT_FOUND_MEMBER);
        }
    }

    public MemberRespDTO.GetMissionRecordRespDTO getMissionRecord (Member persistedMember) {

        MissionStat missionStat = missionHistoryRepository.findMissionStatsByMember(persistedMember.getId(), LocalDate.now().withDayOfMonth(1).atStartOfDay());

        int missionSuccessRate;
        if (missionStat.getSuccess() == null){
            missionSuccessRate = 0;
        }
        else{
            missionSuccessRate = (int) (missionStat.getSuccess() / missionStat.getTotal() *100);
        }

        return MemberRespDTO.GetMissionRecordRespDTO.builder()
                .missionSuccessRate(missionSuccessRate)
                .consecutiveSuccessCnt(persistedMember.getConsecutiveSuccessCnt())
                .build();
    }

    public void getMissionHistory (Long memberId){
        List<MissionHistory> missionHistoryList = missionHistoryRepository.findAllByMemberId(memberId);
        return;
    }

    // 주 마다 사용자 연속 성공 횟수 정리
    @Scheduled(cron = "0 0 0 * * 0")
    public void asdf(){
        memberRepository.findAll().forEach(Member::initConsecutiveSuccessCnt);
    }

    @Transactional
    public MemberRespDTO.SimpleAPIRespDTO changePassword(String email, String newPassword) {
        // 이 엔드포인트는 "로그인할 수 없는 사용자"가 쓰므로 SecurityConfig 에서 permitAll 로 열려 있다.
        // 따라서 이메일 인증 티켓이 유일한 방어선이다 — 없으면 이메일만 알아도 비밀번호가 바뀐다 (C-1·C-2).
        consumeVerification(email);

        Member member = memberRepository.findByEmail(email).orElseThrow(() -> new MemberException(MemberErrorCode.CANT_FOUND_MEMBER));

        String encode = encoder.encode(newPassword);

        member.updatePassword(encode);
        return MemberRespDTO.SimpleAPIRespDTO.builder().isSuccess(true).build();
    }
}
