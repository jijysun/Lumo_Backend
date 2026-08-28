package Lumo.lumo_backend.global.loadtest;

import Lumo.lumo_backend.domain.member.entity.Member;
import Lumo.lumo_backend.domain.member.entity.memberEnum.Login;
import Lumo.lumo_backend.domain.member.entity.memberEnum.MemberRole;
import Lumo.lumo_backend.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 부하 테스트용 회원을 서버 기동 시 시딩한다.
 *
 * <p>인증이 필요한 API(로그인 · 무중단 실측 · 인증 성능 측정)는 실제 회원 레코드를 요구한다.
 * JWT를 시크릿으로 미리 서명해 넣더라도 {@code JWTProvider.getAuthentication()} 이
 * {@code CustomUserDetailsService.loadUserByUsername()} → {@code findByEmail()} 을 타므로,
 * DB에 행이 없으면 401이 된다. 따라서 시딩을 우회할 수 없다.
 *
 * <p>기동 방법 — {@code .env} 가 아니라 셸 환경변수로 주입한다.
 * ({@code .env} 는 배포 때마다 workflow 가 통째로 덮어쓴다)
 * <pre>
 * LOADTEST_SEED_ENABLED=true LOADTEST_SEED_USER_COUNT=500 sudo -E docker compose up -d Blue
 * </pre>
 *
 * <p>생성 계정 — {@code loadtest_0001@loadtest.invalid} ~ {@code loadtest_NNNN@...},
 * 비밀번호는 전 계정 공통.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "loadtest.seed", name = "enabled", havingValue = "true")
public class LoadTestUserSeeder implements ApplicationRunner {

    public static final String EMAIL_PREFIX = "loadtest_";

    private final MemberRepository memberRepository;
    private final BCryptPasswordEncoder encoder;

    @Value("${loadtest.seed.user-count:0}")
    private int targetUserCount;

    /** RFC 6761 예약 TLD. 실제로 해석되지 않으므로 실 SMTP 를 물어도 외부로 나가지 않는다. */
    @Value("${loadtest.seed.email-domain:loadtest.invalid}")
    private String emailDomain;

    @Value("${loadtest.seed.password:loadtest1234!}")
    private String rawPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (targetUserCount <= 0) {
            log.info("[LoadTestUserSeeder] user-count={} → 시딩 생략", targetUserCount);
            return;
        }

        long existingCount = memberRepository.countByEmailStartingWith(EMAIL_PREFIX);
        if (existingCount >= targetUserCount) {
            log.info("[LoadTestUserSeeder] 기존 {}명 >= 목표 {}명 → 시딩 생략", existingCount, targetUserCount);
            return;
        }

        // BCrypt 는 1회 해싱에 수십 ms 가 든다(strength 10 기준). 전 계정이 같은 비밀번호를 쓰므로
        // 해시를 한 번만 만들어 재사용한다. 계정마다 encode() 를 부르면 기동이 수십 초 단위로 늘어난다.
        String encodedPassword = encoder.encode(rawPassword);

        List<Member> members = new ArrayList<>();
        for (int i = (int) existingCount + 1; i <= targetUserCount; i++) {
            String suffix = String.format("%04d", i);
            members.add(Member.create(
                    EMAIL_PREFIX + suffix + "@" + emailDomain,
                    "부하테스트유저" + suffix,
                    encodedPassword,
                    Login.NORMAL,
                    MemberRole.USER));
        }
        memberRepository.saveAll(members);

        log.info("[LoadTestUserSeeder] {}명 신규 생성 (기존 {}명 → 총 {}명), 이메일 {}0001~{}@{}",
                members.size(), existingCount, targetUserCount,
                EMAIL_PREFIX, String.format("%04d", targetUserCount), emailDomain);
    }
}
