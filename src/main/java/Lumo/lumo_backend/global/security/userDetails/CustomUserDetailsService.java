package Lumo.lumo_backend.global.security.userDetails;


import Lumo.lumo_backend.domain.member.repository.MemberRepository;
import Lumo.lumo_backend.global.apiResponse.status.ErrorCode;
import Lumo.lumo_backend.global.exception.GeneralException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService { // 상속으로 자체적인 DB 조회

    private final MemberRepository memberRepository;
    private final MeterRegistry meterRegistry;

    /**
     * 인증된 요청마다 발생하는 회원 조회의 소요 시간 측정용
     *
     * G-6(클레임 확장)의 효과를 측정하는 지표다. 클레임 확장이 끝나면 이 호출 자체가 사라지므로 _count 가 0 으로 떨어지는 것이 곧 개선의 증거가 된다.
     *
     * 히스토그램은 켜지 않는다 — 내부 구간은 기여도 분해용이라 평균(_sum/_count)이면 충분하고,
     * 구간마다 버킷을 만들면 블루-그린 2컨테이너 기준 시계열이 수백 개로 뿔어남
     */
    private Timer loadTimer;

    @PostConstruct
    void initMetrics() {
        loadTimer = Timer.builder("auth.userdetails.load")
                .description("JWT 인증 시 회원 조회(findByEmail) 소요 시간")
                .register(meterRegistry);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // username 이지만, 내부에는 Email - 사용자 별 고유 값이 들어있음!

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return memberRepository.findByEmail(username)
                    .map(CustomUserDetails::new) // 이후 @AuthenticationPrincipal 사용으로 Controller 계층에서 받도록
                    .orElseThrow(() -> new GeneralException(ErrorCode.AUTH_UNAUTHORIZED)); // 일단 GeneralException으로?
        } finally {
            // 실패(회원 없음)도 DB 왕복은 발생했으므로 함께 기록
            sample.stop(loadTimer);
        }
    }
}
