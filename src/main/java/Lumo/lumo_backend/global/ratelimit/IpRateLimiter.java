package Lumo.lumo_backend.global.ratelimit;

import Lumo.lumo_backend.global.apiResponse.status.ErrorCode;
import Lumo.lumo_backend.global.exception.GeneralException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 클라이언트 IP 단위 호출 횟수 제한 (M-7).
 *
 * <p>사용자 열거(user enumeration) 대응. 이메일 존재 여부를 알려주는 조회 API 는 회원가입 UX 상
 * 응답 자체를 감출 수 없어, <b>같은 출처가 대량으로 훑는 것</b>을 막는 쪽이 실질적인 방어가 된다.
 * 이메일 단위로 세면 공격자가 이메일을 바꿔가며 우회하므로 반드시 IP 기준이어야 한다.
 *
 * <p>H-4 와 같은 이유로 카운터를 되읽지 않는다 — value serializer 가
 * {@code GenericJackson2JsonRedisSerializer} 라 {@code INCR} 이 만든 평문 정수를
 * {@code get()} 으로 읽으면 JSON 역직렬화에서 깨진다. {@code INCR} 의 반환값만 쓴다.
 */
@Component
@RequiredArgsConstructor
public class IpRateLimiter {

    private static final String KEY_PREFIX = "ratelimit:";

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * @param action  제한 대상 구분자 (엔드포인트별로 별도 버킷을 쓴다)
     * @param limit   허용 횟수
     * @param window  집계 창
     */
    public void check(HttpServletRequest request, String action, int limit, Duration window) {
        String key = KEY_PREFIX + action + ":" + resolveClientIp(request);

        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            return; // Redis 이상 시 기능을 막지 않는다(가용성 우선)
        }

        if (count == 1L) {
            redisTemplate.expire(key, window);
        }

        if (count > limit) {
            throw new GeneralException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    /**
     * nginx 뒤에 있으므로 remoteAddr 은 항상 프록시 IP 다. X-Forwarded-For 의 첫 항목을 쓴다.
     * ⚠️ 이 헤더는 클라이언트가 위조할 수 있다 — nginx 가 덮어쓰도록 설정돼 있어야 신뢰할 수 있다
     * (G-16 nginx 형상관리 시 {@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for} 확인).
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");

        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
