package Lumo.lumo_backend.domain.email.support;

import Lumo.lumo_backend.global.redis.MailStream;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 재시도를 포기한 항목을 DLQ 로 옮긴다 (A-6 / G-2).
 *
 * <p>DLQ 로 보낸 뒤에는 <b>반드시 원본을 XACK</b> 해야 한다. 안 하면 PEL 에 남아
 * 회수 루프가 같은 항목을 계속 집어 들고, DLQ 에는 중복이 쌓인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailDeadLetterPublisher {

    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;

    private Counter dlqCounter;

    @PostConstruct
    void initMetrics() {
        dlqCounter = Counter.builder("mail.dlq.total")
                .description("재시도를 포기하고 DLQ 로 보낸 메일 건수")
                .register(meterRegistry);
    }

    /**
     * @param record         원본 스트림 레코드
     * @param reason         포기 사유 (영구 실패 유형 또는 재시도 초과)
     * @param deliveryCount  전달 시도 횟수. Streams 가 세어 주므로 별도 카운터가 필요 없다
     */
    public void publish(MapRecord<String, Object, Object> record, String reason, long deliveryCount) {
        try {
            Map<String, String> payload = new LinkedHashMap<>();

            // 원본 필드를 그대로 옮긴다. 나중에 수동 재발송할 떄
            record.getValue().forEach((k, v) ->
                    payload.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));

            payload.put(MailStream.FIELD_REASON, reason);
            payload.put(MailStream.FIELD_FAILED_AT, Instant.now().toString());
            payload.put(MailStream.FIELD_DELIVERY_COUNT, String.valueOf(deliveryCount));

            redisTemplate.opsForStream().add(
                    StreamRecords.newRecord().in(MailStream.DLQ_KEY).ofMap(payload),
                    XAddOptions.maxlen(MailStream.DLQ_MAX_LEN).approximateTrimming(true));

            dlqCounter.increment();
            log.error("[MailDLQ] {} moved to DLQ — reason={}, deliveries={}",
                    record.getId(), reason, deliveryCount);

        } catch (Exception e) {
            /*
             * DLQ 적재가 실패해도 원본은 호출부에서 XACK 된다.
             * 여기서 예외를 올리면 XACK 을 못 해 같은 항목이 영원히 회수·실패를 반복한다.
             */
            log.error("[MailDLQ] failed to publish {} to DLQ", record.getId(), e);
        }
    }
}
