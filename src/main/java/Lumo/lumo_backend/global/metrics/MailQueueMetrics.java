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

    @PostConstruct
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
