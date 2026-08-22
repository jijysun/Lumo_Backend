package Lumo.lumo_backend.global.metrics;

import Lumo.lumo_backend.global.redis.MailStream;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 메일 발송 큐의 적체 관측
 *
 * <p> <b>{@code mail.queue.depth} 의 의미가 A-5 에서 바뀌었다.</b>
 * List 시절 {@code LLEN} 은 "대기 중인 작업 수"였지만, Streams 의 {@code XLEN} 은
 * <b>보관 중인 엔트리 수</b>다 — {@code XACK} 해도 줄지 않고 {@code MAXLEN} 트리밍으로만 줄어든다.
 *
 * <p>따라서 <b>적체 감시는 {@code mail.queue.pending} 으로 옮겨간다.</b>
 * 이 값이 곧 "읽었지만 아직 확인되지 않은" 진짜 미처리 수다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailQueueMetrics {

    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    void register() {
        Gauge.builder("mail.queue.depth", redisTemplate, MailQueueMetrics::streamLength)
                .description("스트림에 보관된 엔트리 수 (XLEN). ACK 해도 줄지 않으며 MAXLEN 트리밍으로만 감소")
                .register(meterRegistry);

        Gauge.builder("mail.queue.pending", redisTemplate, MailQueueMetrics::pendingCount)
                .description("확인되지 않은 미처리 작업 수 (XPENDING). 적체·워커 사망 감지의 실제 지표")
                .register(meterRegistry);
    }

    private static double streamLength(RedisTemplate<String, String> template) {
        return safe(() -> {
            Long size = template.opsForStream().size(MailStream.KEY);
            return size == null ? 0d : size;
        });
    }

    private static double pendingCount(RedisTemplate<String, String> template) {
        return safe(() -> {
            PendingMessagesSummary summary =
                    template.opsForStream().pending(MailStream.KEY, MailStream.GROUP);
            return summary == null ? 0d : summary.getTotalPendingMessages();
        });
    }

    /**
     * Redis 장애·그룹 부재 시 예외를 던지면 <b>해당 스크레이프의 다른 지표까지 통째로 유실</b>된다.
     * NaN 은 Prometheus 에서 "값 없음"으로 처리된다.
     */
    private static double safe(java.util.function.DoubleSupplier supplier) {
        try {
            return supplier.getAsDouble();
        } catch (Exception e) {
            log.warn("[MailQueueMetrics] 조회 실패 - {}", e.getMessage());
            return Double.NaN;
        }
    }
}
