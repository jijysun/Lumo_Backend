package Lumo.lumo_backend.domain.email.service;

import Lumo.lumo_backend.domain.member.exception.MemberException;
import Lumo.lumo_backend.global.redis.MailStream;
import Lumo.lumo_backend.global.redis.MailStreamInitializer;
import Lumo.lumo_backend.domain.member.status.MemberErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    /**
     * 워커 루프 종료 플래그 (A-5 / G-5).
     *
     * <p>이전 {@code while(true)} 는 종료 신호를 받을 방법이 없었다. {@code setAwaitTerminationSeconds}
     * 가 걸려 있어도 무한 루프는 인터럽트로만 끝나므로, 블루-그린 전환 때 <b>처리 중이던 메일이 유실</b>됐다.
     * {@code volatile} 이라야 다른 스레드(@PreDestroy)의 변경이 워커 스레드에 즉시 보인다.
     */
    private volatile boolean running = true;

    /**
     * 블로킹 대기 시간. {@code AsyncConfig} 의 awaitTermination 보다 <b>충분히 짧아야</b>
     * 종료 요청 후 그 시간 안에 루프가 빠져나온다.
     */
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);

    private final JavaMailSender mailSender;
    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final MailStreamInitializer streamInitializer;

    /** SMTP 왕복 시간. G-4 의 필요 워커 수를 Little's Law 로 역산하는 입력값이 된다. */
    private Timer sendTimer;

    /**
     * 발송 성공·실패 건수.
     * 그래서 "1000건 처리"가 큐 배출 속도인지 실제 수락 건수인지 구분할 수 없었다.
     * 이 카운터가 그 주장을 방어할 수 있게 해준다.
     */
    private Counter sendSuccess;
    private Counter sendFailure;

    @PostConstruct
    void initMetrics() {
        sendTimer = Timer.builder("mail.send.duration")
                .description("SMTP 발송 1건 소요 시간")
                .register(meterRegistry);

        sendSuccess = Counter.builder("mail.send.result")
                .description("메일 발송 결과 건수")
                .tag("result", "success")
                .register(meterRegistry);

        sendFailure = Counter.builder("mail.send.result")
                .description("메일 발송 결과 건수")
                .tag("result", "fail")
                .register(meterRegistry);
    }

    /**
     * 종료 신호. 루프는 다음 반복에서 조건을 보고 스스로 빠져나온다 (A-5 / G-5).
     * 최대 {@link #BLOCK_TIMEOUT} 만큼 늦게 반응한다.
     */
    @PreDestroy
    void shutdown() {
        log.info("[EmailService] shutdown requested - stopping mail workers");
        running = false;
    }

    /**
     * 메일 발송 워커 (A-5 / G-1).
     *
     * <p><b>핵심은 XACK 의 위치다.</b> {@code XREADGROUP} 으로 읽어도 항목은 PEL 에 남고,
     * 발송이 성공한 뒤에야 {@code XACK} 으로 제거한다. 그 사이에 죽으면 항목이 PEL 에 남아
     * {@code MailRecoveryScheduler} 가 회수한다 — 이것이 at-least-once 다.
     *
     * <p>⚠️ 대가로 <b>중복 발송이 가능</b>하다. 발송 직후 XACK 직전에 죽으면 재전달된다.
     * 인증 코드는 중복 수신이 치명적이지 않아 수용하는 트레이드오프다.
     */
    @Async("mailWorkerExecutor")
    public void startMailWorker(int workerId) {
        Consumer consumer = Consumer.from(MailStream.GROUP, MailStream.consumerName(workerId));
        log.info("[EmailService] worker {} started as consumer '{}'", workerId, consumer.getName());

        while (running) {
            try {
                /*
                 * RedisConfig 의 hashValueSerializer 가 GenericJackson2JsonRedisSerializer 라
                 * opsForStream() 의 타입 인자가 <String, Object, Object> 로 잡힌다.
                 * 값은 String 으로 왕복하지만 선언 타입은 Object 이므로 꺼낼 때 변환한다.
                 */
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        consumer,
                        StreamReadOptions.empty().block(BLOCK_TIMEOUT).count(1),
                        // ">" = 이 그룹이 아직 아무에게도 전달하지 않은 항목만
                        StreamOffset.create(MailStream.KEY, ReadOffset.lastConsumed()));

                if (records == null || records.isEmpty()) {
                    continue;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    process(record);
                }

            } catch (Exception e) {
                if (!running) {
                    break; // 종료 중 발생한 연결 예외는 정상 흐름
                }
                // 스트림·그룹이 사라진 경우(FLUSHDB 등)는 재생성으로 복구된다.
                // 복구하지 않으면 워커 전원이 NOGROUP 으로 무한 재시도하며 아무 일도 하지 않는다.
                if (streamInitializer.recreateIfMissing(e)) {
                    continue;
                }

                log.error("[EmailService] worker {} error, retry after 1s", workerId, e);
                sleepQuietly();
            }
        }
        log.info("[EmailService] worker {} stopped", workerId);
    }

    /**
     * 한 건을 처리한다. <b>발송이 성공해야만 XACK 한다</b> —
     * 예외가 나면 여기서 빠져나가 XACK 에 도달하지 못하고 PEL 에 남는다.
     *
     * <p>{@code MailRecoveryScheduler} 가 회수한 항목도 같은 경로를 타야 하므로 공개한다.
     * 회수 항목을 별도 로직으로 처리하면 두 경로의 동작이 조용히 갈라진다.
     */
    public void process(MapRecord<String, Object, Object> record) {
        String email = field(record, MailStream.FIELD_EMAIL);
        String code = field(record, MailStream.FIELD_CODE);

        if (email == null || code == null) {
            // 필드가 깨진 항목은 재시도해도 영원히 실패한다. 회수 루프에 남기지 않고 즉시 확인 처리한다.
            log.error("[EmailService] malformed record {} - acking to avoid infinite retry", record.getId());
            redisTemplate.opsForStream().acknowledge(MailStream.KEY, MailStream.GROUP, record.getId());
            return;
        }

        sendEmail(email, code);

        redisTemplate.opsForStream().acknowledge(MailStream.KEY, MailStream.GROUP, record.getId());
    }

    private static String field(MapRecord<String, Object, Object> record, String name) {
        Object value = record.getValue().get(name);
        return value == null ? null : String.valueOf(value);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // 측정용 메서드
    public void sendEmail(String email, String code) {
        Timer.Sample sample = Timer.start(meterRegistry);
        boolean success = false;
        try {
            doSendEmail(email, code);
            success = true;
        } finally {
            // 실패도 SMTP 왕복 시간을 소비했으므로 함께 기록한다.
            sample.stop(sendTimer);
            (success ? sendSuccess : sendFailure).increment();
        }
    }

    // 실 메서드. 현 Service 내에서만 접근하게끔.
    private void doSendEmail(String email, String code) {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper;

        try {
            helper = new MimeMessageHelper(msg, true, "utf-8");
            helper.setTo(email);
            helper.setFrom("no-reply@mail.com", "no-reply@mail.com");
            helper.setSubject("Lumo 인증 이메일 알림.");
            helper.setText("<!DOCTYPE html>\n" +
                    "<html lang=\"ko\">\n" +
                    "<head>\n" +
                    "    <meta charset=\"UTF-8\">\n" +
                    "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                    "    <style>\n" +
                    "        @import url('https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@400;700&display=swap');\n" +
                    "        \n" +
                    "        body {\n" +
                    "            font-family: 'Noto Sans KR', 'Malgun Gothic', 'Apple SD Gothic Neo', Arial, sans-serif;\n" +
                    "            background-color: #f4f6f9;\n" +
                    "            margin: 0;\n" +
                    "            padding: 0;\n" +
                    "        }\n" +
                    "        .container {\n" +
                    "            width: 100%;\n" +
                    "            max-width: 580px;\n" +
                    "            margin: 40px auto;\n" +
                    "            background-color: #ffffff;\n" +
                    "            border-radius: 12px;\n" +
                    "            box-shadow: 0 6px 15px rgba(0, 0, 0, 0.05);\n" +
                    "            overflow: hidden;\n" +
                    "            border: 1px solid #e9ecef;\n" +
                    "        }\n" +
                    "        .header {\n" +
                    "            background-color: #ffffff;\n" +
                    "            padding: 30px 20px 20px;\n" +
                    "            text-align: center;\n" +
                    "        }\n" +
                    "        .header h2 {\n" +
                    "            font-size: 24px;\n" +
                    "            color: #212529;\n" +
                    "            font-weight: 700;\n" +
                    "            margin: 0;\n" +
                    "        }\n" +
                    "        .content {\n" +
                    "            padding: 20px;\n" +
                    "            text-align: center;\n" +
                    "            line-height: 1.6;\n" +
                    "            color: #495057;\n" +
                    "        }\n" +
                    "        .verification-code-container {\n" +
                    "            margin: 30px 0;\n" +
                    "            text-align: center;\n" +
                    "        }\n" +
                    "        .verification-code {\n" +
                    "            display: inline-block;\n" +
                    "            background-color: #eaf3ff;\n" +
                    "            padding: 15px 30px;\n" +
                    "            font-size: 28px;\n" +
                    "            font-weight: bold;\n" +
                    "            letter-spacing: 3px;\n" +
                    "            border-radius: 8px;\n" +
                    "            color: #0056b3;\n" +
                    "            border: 1px dashed #ced4da;\n" +
                    "            -webkit-user-select: all;\n" +
                    "            -moz-user-select: all;\n" +
                    "            -ms-user-select: all;\n" +
                    "            user-select: all;\n" +
                    "        }\n" +
                    "        .instruction {\n" +
                    "            margin-top: 10px;\n" +
                    "            font-size: 14px;\n" +
                    "            color: #6c757d;\n" +
                    "        }\n" +
                    "        .footer {\n" +
                    "            text-align: center;\n" +
                    "            padding: 20px;\n" +
                    "            border-top: 1px solid #e9ecef;\n" +
                    "            font-size: 12px;\n" +
                    "            color: #adb5bd;\n" +
                    "            background-color: #f8f9fa;\n" +
                    "        }\n" +
                    "        .logo {\n" +
                    "            width: 50px;\n" +
                    "            height: 50px;\n" +
                    "            background-color: #007bff;\n" +
                    "            border-radius: 50%;\n" +
                    "            margin: 0 auto 15px;\n" +
                    "        }\n" +
                    "    </style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "<div class=\"container\">\n" +
                    "    <div class=\"header\">\n" +
                    "        \n" +
                    "        <div class=\"logo\"></div>\n" +
                    "        <h2>Lumo 이메일 인증</h2>\n" +
                    "    </div>\n" +
                    "    <div class=\"content\">\n" +
                    "        <p>안녕하세요, Lumo입니다. 이메일 주소 인증을 위해 아래 코드를 사용해 주세요.</p>\n" +
                    "        <div class=\"verification-code-container\">\n" +
                    "            <span class=\"verification-code\">  " + code + "  </span>\n" +
                    "        </div>\n" +
                    "        <p>이 코드는 3분 동안 유효합니다.</p>\n" +
                    "        <p class=\"instruction\">코드를 복사하여 앱에 붙여넣어 주세요.</p>\n" +
                    "    </div>\n" +
                    "    <div class=\"footer\">\n" +
                    "        <p>이 메일은 발신 전용입니다. 문의사항은 관리자 이메일로 문의 부탁 드립니다.</p>\n" +
                    "        <p>&copy; 2026 Lumo. All Rights Reserved.</p>\n" +
                    "    </div>\n" +
                    "</div>\n" +
                    "</body>\n" +
                    "</html>", true);
            helper.setReplyTo("no-reply@mail.com");
            mailSender.send(msg);
        } catch (MessagingException e) {
            e.printStackTrace();
            throw new MemberException(MemberErrorCode.CANT_SEND_EMAIL);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }

//        log.info("[EmailService - requestVerificationCode] saved code {} to {}", redisTemplate.opsForValue().get(email), email);
    }
}
