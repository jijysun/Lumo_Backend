package Lumo.lumo_backend.domain.email.support;

import jakarta.mail.internet.AddressException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;

import java.io.UnsupportedEncodingException;

/**
 * 발송 실패 예외를 재시도 가능 여부로 분류
 *
 * {@code mailSender.send()} 가 던지는 MailSendException 은 Spring 의 <b>런타임</b> 예외라 기존 {@code catch (MessagingException)} 에
 * 걸리지 않았다. 그대로 워커 catch 까지 올라가 <b>로그만 남고 작업이 소멸</b>했고,
 * 그래서 "1000건 처리" 가 수락 건수인지 큐 배출 속도인지 구분할 수 없었다.
 */
@Slf4j
public final class MailFailureClassifier {

    private MailFailureClassifier() {
    }

    public static MailFailure classify(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {

            // 주소 형식 자체가 틀렸다 || 인코딩 불가 → 재시도해도 동일하게 실패
            if (t instanceof AddressException
                    || t instanceof UnsupportedEncodingException
                    || t instanceof MailParseException) {
                return MailFailure.PERMANENT;
            }

            /*
             * 인증 실패(535)는 자격증명 설정 오류. 재시도는 무의미하고, 오히려 반복 실패로 SMTP 서버가 계정 락 가능.
             * "고치면 되는" 문제이므로 로그를 크게 남긴다.
             */
            if (t instanceof MailAuthenticationException) {
                log.error("[MailFailure] SMTP 인증 실패 — 자격증명 설정을 확인하세요", t);
                return MailFailure.PERMANENT;
            }

            // MailSendException: 실제 원인을 내부 맵에 감춰둔다. 꺼내서 다시 판정한다.
            if (t instanceof MailSendException mse) {
                MailFailure nested = classifyNested(mse);
                if (nested != null) {
                    return nested;
                }
            }

            Integer code = replyCode(t);
            if (code != null) {
                return fromReplyCode(code);
            }
        }

        // 판단 근거가 없으면 재시도 쪽으로 둔다. 잘못 버리는 것보다 낫다.
        return MailFailure.TRANSIENT;
    }

    private static MailFailure classifyNested(MailSendException e) {
        Exception[] nested = e.getMessageExceptions();
        if (nested == null || nested.length == 0) {
            return null;
        }
        // 한 건이라도 영구 실패면 그 메시지는 다시 보내도 소용없다.
        for (Exception each : nested) {
            if (classify(each) == MailFailure.PERMANENT) {
                return MailFailure.PERMANENT;
            }
        }
        return MailFailure.TRANSIENT;
    }

    private static Integer replyCode(Throwable t) {
        if (t instanceof SMTPAddressFailedException e) {
            return e.getReturnCode();
        }
        if (t instanceof SMTPSenderFailedException e) {
            return e.getReturnCode();
        }
        if (t instanceof SMTPSendFailedException e) {
            return e.getReturnCode();
        }
        return null;
    }

    /**
     * SMTP 응답 코드 규칙 (RFC 5321 §4.2.1)
     * <ul>
     *   <li>4xx — 일시 실패(transient). 재시도 대상</li>
     *   <li>5xx — 영구 실패(permanent). 5.1.1 수신자 없음 · 5.7.x 정책 거부</li>
     * </ul>
     */
    private static MailFailure fromReplyCode(int code) {
        return (code >= 500 && code < 600) ? MailFailure.PERMANENT : MailFailure.TRANSIENT;
    }
}
