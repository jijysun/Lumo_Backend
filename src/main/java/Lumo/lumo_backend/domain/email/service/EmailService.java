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
package Lumo.lumo_backend.domain.email.service;

import Lumo.lumo_backend.domain.member.exception.MemberException;
import Lumo.lumo_backend.domain.member.status.MemberErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    /** 발송 대기 큐 키. MailQueueMetrics 가 같은 키를 봐야 하므로 상수로 노출한다. */
    public static final String QUEUE_KEY = "email_queue";

    private final JavaMailSender mailSender;
    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;

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
     * (measure/s1-raw) 원시 구조 — 요청 스레드에서 바로 비동기 발송을 던진다. 큐도 워커도 없다.
     *
     * Redis 저장을 <b>이 비동기 경로 안에서</b> 하는 것이 핵심이다. 호출부의 중복 검사와 저장 사이가
     * 스레드 경계로 벌어져, 동시 요청 두 건이 모두 검사를 통과하고 각각 메일을 보내게 된다.
     * 포트폴리오 p23 이 지목한 "나노초 단위 다른 스레드 접근" 문제가 바로 이 창이다.
     */
    @Async("rawMailExecutor")
    public void sendEmailAsync(String email, String code) {
        redisTemplate.opsForValue().set(email, code, Duration.ofMinutes(3));
        sendEmail(email, code);
    }

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
