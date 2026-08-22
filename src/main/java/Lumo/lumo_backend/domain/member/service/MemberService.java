package Lumo.lumo_backend.domain.member.service;

import Lumo.lumo_backend.domain.alarm.entity.MissionHistory;
import Lumo.lumo_backend.domain.alarm.entity.repository.MissionHistoryRepository;
import Lumo.lumo_backend.domain.member.dto.MemberReqDTO;
import Lumo.lumo_backend.domain.member.dto.MemberRespDTO;
import Lumo.lumo_backend.domain.member.dto.MissionStat;
import Lumo.lumo_backend.domain.member.entity.memberEnum.Login;
import Lumo.lumo_backend.domain.member.entity.Member;
import Lumo.lumo_backend.domain.member.entity.memberEnum.MemberRole;
import Lumo.lumo_backend.domain.member.exception.MemberException;
import Lumo.lumo_backend.domain.member.repository.MemberRepository;
import Lumo.lumo_backend.domain.member.status.MemberErrorCode;
import Lumo.lumo_backend.global.redis.MailStream;
import Lumo.lumo_backend.global.security.jwt.JWT;
import Lumo.lumo_backend.global.security.jwt.JWTProvider;
import Lumo.lumo_backend.global.security.token.RefreshTokenKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
@EnableAsync
public class MemberService {

    private final MemberRepository memberRepository;
    private final MissionHistoryRepository missionHistoryRepository;
    // (M-9) raw type 이면 반환값이 Object 라 매번 캐스팅해야 하고 타입 오류가 런타임에야 드러난다.
    private final RedisTemplate<String, String> redisTemplate;
    private final JWTProvider jwtProvider;
    private final BCryptPasswordEncoder encoder;

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 4;

    /** 이메일 인증 성공 티켓. signIn / changePassword 가 1회 소비한다 (C-2). */
    private static final String VERIFIED_KEY_PREFIX = "verified:";
    private static final Duration VERIFIED_TTL = Duration.ofMinutes(10);

    /*
     * 인증 코드 브루트포스 방어 (H-4).
     *
     * 잠금 상태를 카운터 키에서 읽지 않고 별도 키로 둔다.
     * RedisConfig 의 value serializer 가 GenericJackson2JsonRedisSerializer 라
     * INCR 이 만든 평문 정수("1")를 get() 으로 되읽으면 JSON 역직렬화에서 깨진다.
     * → 카운터는 INCR 의 "반환값"만 쓰고(원자적), 잠금 여부는 hasKey 로 판정한다.
     */
    private static final String VERIFY_FAIL_PREFIX = "verify_fail:";
    private static final String VERIFY_LOCK_PREFIX = "verify_lock:";
    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final Duration VERIFY_LOCK_TTL = Duration.ofMinutes(10);

    /**
     * java.util.Random 은 48bit LCG 라 출력 2개만 관측하면 시드를 복원해 이후 코드를 예측할 수 있다.
     * 인증 코드는 보안 목적이므로 SecureRandom 을 쓴다. 인스턴스 생성 비용이 있어 재사용한다(thread-safe).
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();


    /**
     * (G-6) 시그니처를 Member -> String email 로 바꿨다.
     *
     * 이전에는 필터가 방금 조회한 회원을 받아 email 만 꺼낸 뒤 findByEmail 로 <b>다시 조회</b>했다.
     * 인증 경로에서 DB 조회가 사라지면서 이 중복이 자연히 정리된다. email 은 JWT 의 sub 에 있다.
     */
    public MemberRespDTO.GetLoginDTO getLogin(String email) {

        if (email == null || email.isBlank()) {
            throw new MemberException(MemberErrorCode.CANT_FOUND_MEMBER);
        }

        Optional<Member> byEmail = memberRepository.findByEmail(email);
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
        // 잠금 중에는 코드 재발급도 막는다. 막지 않으면 공격자가 코드만 갈아끼우며 메일 발송량을 소진시킨다
        // (SES 전환 시 발송 한도에 직결된다 — G-23).
        ensureNotLocked(email);

        String code = generateVerificationCode();
        Boolean ifAbsent = redisTemplate.opsForValue().setIfAbsent(email, code, Duration.ofMinutes(3));

        if (Boolean.FALSE.equals(ifAbsent)){
//            log.info("[MemberService - requestVerificationCode] already send to {} with {}", email, redisTemplate.opsForValue().get(email));
            throw new MemberException(MemberErrorCode.ALREADY_SEND); // 따닥 방지
        }
        else{
            /*
             * (A-5) List LPUSH → Stream XADD.
             *
             * 이전 "email:code" 문자열은 이메일에 ':' 가 섞이면 split 이 깨졌다. 필드 맵이라 그 문제가 사라진다.
             * MAXLEN 은 필수다 — XACK 은 PEL 에서만 빼고 엔트리는 스트림에 남으므로,
             * 트리밍하지 않으면 메모리가 단조 증가한다. '~' 근사 트리밍이 정확 트리밍보다 훨씬 싸다.
             */
            redisTemplate.opsForStream().add(
                    StreamRecords.newRecord()
                            .in(MailStream.KEY)
                            .ofMap(Map.of(MailStream.FIELD_EMAIL, email,
                                          MailStream.FIELD_CODE, code)),
                    XAddOptions.maxlen(MailStream.MAX_LEN).approximateTrimming(true));
        }
    }
    public String generateVerificationCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);

        for (int i = 0; i < CODE_LENGTH; i++) {
            int randomIndex = SECURE_RANDOM.nextInt(CHARACTERS.length());

            code.append(CHARACTERS.charAt(randomIndex));
        }

        return code.toString();
    }


    public void verifyCode(String email, String code) {
        ensureNotLocked(email);

        String savedCode = redisTemplate.opsForValue().get(email);

        if (savedCode == null || !savedCode.equals(code)) {
            registerVerifyFailure(email);
            throw new MemberException(MemberErrorCode.WRONG_CODE);
        }

        // 성공했으니 실패 카운터를 해제한다. 남겨두면 정상 사용자가 다음 인증에서 불이익을 받는다.
        redisTemplate.delete(VERIFY_FAIL_PREFIX + email);

        // 검증에 쓴 코드는 즉시 폐기한다.
        // 남겨두면 3분 TTL 이 끝날 때까지 같은 코드로 무제한 재검증이 가능했다.
        redisTemplate.delete(email);

        // 인증에 성공했다는 사실을 티켓으로 남긴다. signIn / changePassword 가 이 티켓을 소비한다.
        // 이전에는 성공 사실을 어디에도 저장하지 않아 verify-code 를 건너뛰고 바로 signIn 을 호출해도 가입이 됐다.
        redisTemplate.opsForValue().set(VERIFIED_KEY_PREFIX + email, "true", VERIFIED_TTL);
    }

    /** 잠금 상태면 즉시 거절한다. 카운터를 읽지 않고 잠금 키 존재 여부만 본다(상단 주석 참고). */
    private void ensureNotLocked(String email) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(VERIFY_LOCK_PREFIX + email))) {
            throw new MemberException(MemberErrorCode.TOO_MANY_VERIFY_ATTEMPTS);
        }
    }

    /**
     * 인증 실패를 1회 기록하고, 상한에 도달하면 코드를 폐기한 뒤 잠금을 건다.
     *
     * <p>{@code INCR} 은 원자적이고 <b>증가 후 값을 반환</b>하므로 별도 조회 없이 판정할 수 있다.
     * 키가 없으면 Redis 가 0 에서 시작하므로 첫 호출의 반환값이 1 이고, 그때만 TTL 을 건다.
     */
    private void registerVerifyFailure(String email) {
        String failKey = VERIFY_FAIL_PREFIX + email;
        Long attempts = redisTemplate.opsForValue().increment(failKey);

        if (attempts == null) {
            return;
        }

        if (attempts == 1L) {
            redisTemplate.expire(failKey, VERIFY_LOCK_TTL);
        }

        if (attempts >= MAX_VERIFY_ATTEMPTS) {
            // 코드를 폐기해 현재 코드로는 더 이상 시도할 수 없게 하고, 재발급까지 잠근다.
            redisTemplate.delete(email);
            redisTemplate.opsForValue().set(VERIFY_LOCK_PREFIX + email, "locked", VERIFY_LOCK_TTL);
        }
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

        try {
            memberRepository.save(Member.create(dto.getEmail(), dto.getUsername(), encoder.encode(dto.getPassword()), Login.NORMAL, MemberRole.USER));
        } catch (DataIntegrityViolationException e) {
            // (A-2) 위 findByEmail 검사와 이 save 사이에 다른 요청이 같은 이메일로 먼저 저장할 수 있다.
            // 애플리케이션 단 "조회 후 저장" 만으로는 이 창을 닫을 수 없고, uk_member_email 이 최종 방어선이다.
            // 제약이 없던 시절에는 같은 이메일로 두 행이 그대로 생겼다 (M-13).
            throw new MemberException(MemberErrorCode.EXIST_MEMBER);
        }

//        log.info("[MemberService - signIn] Success to signIn -> {}, {}", dto.getEmail(), dto.getUsername());
    }

    /**
     * @param deviceId 기기 식별자 (H-9). 컨트롤러가 {@code X-Device-Id} 헤더에서 꺼내 넘긴다.
     *                 헤더가 없으면 {@code "default"} 로 떨어져 기존과 동일하게 동작한다.
     */
    public MemberRespDTO.MemberInfoDTO login(MemberReqDTO.LoginReqDTO dto, String deviceId) {
        Member member = memberRepository.findByEmail(dto.getEmail()).orElseThrow(() -> new MemberException(MemberErrorCode.CANT_FOUND_MEMBER));
        if (!encoder.matches(dto.getPassword(), member.getPassword())) {
            throw new MemberException(MemberErrorCode.CANT_FOUND_MEMBER);
        }

        List<SimpleGrantedAuthority> authorities = Collections.singletonList(new SimpleGrantedAuthority(member.getRole().toString()));
        Authentication authentication = new UsernamePasswordAuthenticationToken(member.getEmail(), member.getPassword(), authorities);
        JWT jwt = jwtProvider.generateToken(authentication, member.getId()); // C-3: mid 클레임용

        // TTL 은 RT 자체의 exp 에서 역산한다 (H-2).
        // TTL 없이 set 하면 로그아웃하지 않은 사용자의 키가 영구히 남아 회원 수에 비례해 단조 증가한다.
        // 상수를 복제하지 않고 토큰의 만료 시각을 쓰므로, C-3 로 RT 수명을 늘려도 TTL 이 자동으로 따라간다.
        // (H-9) 기기별 키. 다른 기기의 RT 를 덮어쓰지 않는다.
        redisTemplate.opsForValue().set(
                RefreshTokenKey.refresh(dto.getEmail(), deviceId),
                jwt.getRefreshToken(),
                jwtProvider.getRemainingTime(jwt.getRefreshToken()),
                TimeUnit.MILLISECONDS);

//        log.info("[MemberService - login] Success to login -> {} - {}", dto.getEmail(), jwt.getRefreshToken());

        return MemberRespDTO.MemberInfoDTO.builder().jwt(jwt).username(member.getUsername()).build();
    }

    /**
     * @param deviceId 기기 식별자 (H-9). <b>로그아웃은 그 기기만 끊는다</b> —
     *                 계정 전체를 끊으면 다른 기기에서 쓰던 세션까지 함께 죽는다.
     */
    public void logout (String accessToken, Long memberId, String deviceId){
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new MemberException(MemberErrorCode.CANT_FOUND_MEMBER));
        redisTemplate.delete(RefreshTokenKey.refresh(member.getEmail(), deviceId));

        /*
         * 블랙리스트 등록을 제거했다.
         *
         * 읽는 쪽인 JWTAuthenticationFilter이 사라졌으므로 여기서 쓰기만 계속하면 아무도 보지 않는 키가 Redis 에 쌓일 뿐이다.
         * - AT 를 15분으로 줄인 것이 이 기능을 대체한다.
         *
         * 로그아웃의 실질적 효력은 아래 refresh 키 삭제
         */
        // (G-9) 회전 유예 창에 남은 직전 RT 도 함께 정리.
        redisTemplate.delete(RefreshTokenKey.prevRt(member.getEmail(), deviceId));

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

        /*
         * (M-4) 이전 코드는 (int)(Long / Long * 100) 이라 정수 나눗셈이 먼저 수행됐다.
         * success < total 이면 0, 같으면 1 -> x100 하면 결과가 항상 0 또는 100 뿐이었다.
         * 추가로 total 이 0 이면 ArithmeticException, null 이면 언박싱 NPE 였다
         * (getSuccess() 만 null 검사하고 getTotal() 은 하지 않았다).
         *
         * COUNT/SUM 집계 쿼리라 행 자체는 항상 반환되지만, 대상이 없으면 SUM 이 null 이고
         * COUNT 는 0 이다. 계산식은 Member.updateMissionSuccessRate() 와 동일하게 맞춘다.
         */
        long total = (missionStat == null || missionStat.getTotal() == null) ? 0L : missionStat.getTotal();
        long success = (missionStat == null || missionStat.getSuccess() == null) ? 0L : missionStat.getSuccess();

        int missionSuccessRate = (total == 0L) ? 0 : (int) Math.round(success * 100.0 / total);

        return MemberRespDTO.GetMissionRecordRespDTO.builder()
                .missionSuccessRate(missionSuccessRate)
                .consecutiveSuccessCnt(persistedMember.getConsecutiveSuccessCnt())
                .build();
    }

    public void getMissionHistory (Long memberId){
        List<MissionHistory> missionHistoryList = missionHistoryRepository.findAllByMemberId(memberId);
        return;
    }

    /*
     * (M-5) 주간 연속 성공 횟수 초기화 배치 — 기능 제거 (사용자 결정, 20260818).
     *
     * 어차피 동작하지 않고 있었다: @Transactional 이 없어 영속성 컨텍스트가 유지되지 않으므로
     * dirty checking 이 일어나지 않아 변경이 DB 에 반영되지 않았다.
     * (LumoBackendApplication 에 @EnableScheduling 이 있어 스케줄 자체는 매주 돌고 있었다)
     *
     * 추가로 findAll() 로 전 회원을 메모리에 적재해 회원 수 증가 시 OOM 위험이 있었고,
     * 블루-그린 전환 순간 두 컨테이너가 동시에 살아있으면 중복 실행됐다.
     *
     * 되살릴 경우: @Transactional + @Modifying 벌크 UPDATE 1회 + 단일 실행 락(SET NX EX) 필요.
     */
//    @Scheduled(cron = "0 0 0 * * 0")
//    public void resetConsecutiveSuccessCount(){
//        memberRepository.findAll().forEach(Member::initConsecutiveSuccessCnt);
//    }

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
