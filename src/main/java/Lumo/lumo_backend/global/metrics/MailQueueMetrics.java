/*
 * ⚠️ 측정 전용 브랜치 (measure/s1-raw) — develop 에 병합하지 말 것.
 *
 * 4단계 계측의 [1단계 = 원시 구조] 대조군이다. 2026-02-05 `7a28965` 시점의 메일 파이프라인
 * (전용 스레드풀 없는 @Async · 큐 없음 · 비원자 중복 방지)을 현재 인프라 위에 재현했다.
 *
 * 왜 그 커밋을 그대로 배포하지 않는가 — 당시 코드에는 계측·Flyway·Seeder·arm64 Dockerfile 이
 * 없어 (a) 서버 내부 지표를 하나도 얻을 수 없고 (b) t4g 배포 자체가 어렵다. 측정 도구가 회차마다
 * 다르면 비교가 성립하지 않으므로, 인프라는 현행으로 두고 "메일 파이프라인만" 과거로 되돌린다.
 *
 * 되돌린 것 : 중복 방지 원자성 · 작업 큐 · 워커 풀
 * 유지한 것 : 계측 3종 · 인증 개선(C-3·G-6·G-8·G-9) · 보안 수정 · 배포 인프라
 */
package Lumo.lumo_backend.global.metrics;

import Lumo.lumo_backend.domain.email.service.EmailService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 메일 발송 큐의 적체 관측
 * Gauge 는 스크레이프 시점마다 콜백이 실행된다. Prometheus 주기가 30s 이므로 LLEN 이 30초에 한 번 나가는 수준이라 부담 ㅌ
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailQueueMetrics {

    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;

    // (measure/s1-raw) 원시 구조에는 큐가 없다. 항상 0 인 지표는 오해를 부르므로 등록하지 않는다.
    // @PostConstruct
    void register() {
        Gauge.builder("mail.queue.depth", redisTemplate, MailQueueMetrics::queueSize)
                .description("발송 대기 중인 메일 작업 수 (email_queue 의 LLEN)")
                .register(meterRegistry);
    }

    /**
     * Redis 장애 시 스크레이프 자체가 깨지지 않도록 방어한다.
     * 예외를 던지면 해당 스크레이프의 다른 지표까지 함께 유실된다.
     */
    private static double queueSize(RedisTemplate<String, String> template) {
        try {
            Long size = template.opsForList().size(EmailService.QUEUE_KEY);
            return size == null ? 0d : size;
        } catch (Exception e) {
            log.warn("[MailQueueMetrics] 큐 길이 조회 실패 - {}", e.getMessage());
            return Double.NaN;   // NaN 은 Prometheus 에서 "값 없음"으로 처리된다
        }
    }
}
