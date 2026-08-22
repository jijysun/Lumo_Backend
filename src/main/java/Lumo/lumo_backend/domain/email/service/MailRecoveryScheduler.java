package Lumo.lumo_backend.domain.email.service;

import Lumo.lumo_backend.domain.email.support.MailDeadLetterPublisher;
import Lumo.lumo_backend.global.redis.MailStream;
import Lumo.lumo_backend.global.redis.MailStreamInitializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 죽은 워커가 물고 있던 작업을 회수한다 (A-5 / G-1).
 *
 * <p>워커가 {@code XREADGROUP} 으로 읽은 뒤 {@code XACK} 전에 죽으면 항목이 PEL 에 남는다.
 * 아무도 다시 읽어가지 않으므로 <b>회수 주체가 없으면 그대로 방치</b>된다. 이 스케줄러가 그 역할이다.
 *
 * <p>⚠️ 계획서는 {@code XAUTOCLAIM} 을 적었으나 Spring Data Redis 3.5 의 {@code StreamOperations}
 * 에는 해당 API 가 없다. {@code XPENDING} 으로 후보를 고르고 {@code XCLAIM} 으로 가져오는 2단계로 구현한다.
 * 부수 효과로 {@code XAUTOCLAIM} 이 요구하는 <b>Redis 6.2+ 제약이 사라진다</b>(XPENDING/XCLAIM 은 5.0+).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailRecoveryScheduler {

    private static final Duration IDLE_THRESHOLD = Duration.ofSeconds(MailStream.RECLAIM_IDLE_SECONDS);
    private static final long SCAN_LIMIT = 100L;

    private final RedisTemplate<String, String> redisTemplate;
    private final EmailService emailService;
    private final MailStreamInitializer streamInitializer;
    private final MailDeadLetterPublisher deadLetterPublisher;

    /**
     * ⚠️ 블루-그린 전환 중에는 두 컨테이너가 동시에 이 스케줄러를 돌린다.
     * {@code XCLAIM} 자체는 원자적이라 중복 회수는 발생하지 않고, 같은 목록을 두 번 조회하는
     * 낭비만 있다. M-5(배치 중복 실행)와 같은 성격이라 여기서는 허용하고 기록만 남긴다.
     */
    @Scheduled(fixedDelayString = "30000")
    public void reclaimStalled() {
        try {
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(MailStream.KEY, MailStream.GROUP, Range.unbounded(), SCAN_LIMIT);

            if (pending == null || pending.isEmpty()) {
                return;
            }

            for (PendingMessage message : pending) {
                // 방금 전달된 항목은 정상 처리 중일 수 있다. 뺏으면 중복 발송이 된다.
                if (message.getElapsedTimeSinceLastDelivery().compareTo(IDLE_THRESHOLD) < 0) {
                    continue;
                }

                /*
                 * ⚠️ XCLAIM 은 소유권만 옮긴다. 워커는 ">"(신규 항목)만 읽으므로 회수해 두기만 하면
                 * 아무도 처리하지 않고 새 소유자의 PEL 에 그대로 눌러앉는다.
                 * 그래서 claim 이 돌려준 레코드를 여기서 곧바로 처리한다.
                 *
                 * (A-6) 포기 판정보다 claim 을 먼저 하는 이유 — PendingMessage 에는 ID 만 있고
                 * 본문이 없다. DLQ 에 "무엇이 실패했는지" 를 남기려면 레코드를 손에 쥐어야 한다.
                 */
                List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                        .claim(MailStream.KEY, MailStream.GROUP, MailStream.RECOVERY_CONSUMER,
                                XClaimOptions.minIdle(IDLE_THRESHOLD).ids(message.getId()));

                if (claimed == null || claimed.isEmpty()) {
                    // 그 사이 다른 인스턴스가 가져갔거나 원 소유자가 ACK 했다. 정상 경합.
                    continue;
                }

                long deliveryCount = message.getTotalDeliveryCount();

                /*
                 * 전달 횟수가 곧 재시도 횟수다 — Streams 가 세어 주므로 별도 카운터가 필요 없다.
                 * 상한을 넘으면 계속 회수해봐야 같은 이유로 실패한다(poison message).
                 *
                 * (A-6) 여기서 그냥 XACK 하면 실패 사실이 로그 한 줄로만 남고 본문은 사라진다.
                 * DLQ 로 옮겨야 "몇 건이, 어떤 주소로, 왜" 실패했는지 사후에 확인·재발송할 수 있다.
                 */
                if (deliveryCount >= MailStream.MAX_DELIVERY_COUNT) {
                    for (MapRecord<String, Object, Object> record : claimed) {
                        deadLetterPublisher.publish(record,
                                "max-delivery-exceeded (consumer=" + message.getConsumerName() + ")",
                                deliveryCount);
                        redisTemplate.opsForStream()
                                .acknowledge(MailStream.KEY, MailStream.GROUP, record.getId());
                    }
                    continue;
                }

                log.warn("[MailRecovery] reclaimed {} from {} (delivery #{})",
                        message.getIdAsString(), message.getConsumerName(), deliveryCount);

                for (MapRecord<String, Object, Object> record : claimed) {
                    try {
                        // 실제 전달 횟수를 넘겨야 DLQ 기록의 deliveries 값이 사실과 맞는다.
                        emailService.process(record, deliveryCount);
                    } catch (Exception e) {
                        // 재발송이 또 실패했다. PEL 에 남으므로 다음 주기에 delivery count 가 올라간다.
                        log.error("[MailRecovery] resend failed for {}", record.getId(), e);
                    }
                }
            }

        } catch (Exception e) {
            if (streamInitializer.recreateIfMissing(e)) {
                return;
            }
            // 회수 실패가 애플리케이션을 멈출 이유는 없다. 다음 주기에 다시 시도한다.
            log.error("[MailRecovery] reclaim failed", e);
        }
    }
}
