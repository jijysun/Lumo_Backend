package Lumo.lumo_backend.domain.email.support;

/**
 * 메일 조립·발송 중 발생한 검사 예외를 <b>원인을 보존한 채</b> 감싼다 (A-6 / G-2).
 *
 * <p>이전 코드는 {@code MessagingException} 을 잡아 {@code MemberException(CANT_SEND_EMAIL)} 로 바꿔 던졌다.
 * 그 결과 <b>SMTP 응답 코드가 통째로 사라져</b> 재시도 가치를 판단할 근거가 없었고,
 * 워커 경로에서는 HTTP 상태 코드를 쓸 곳도 없었다.
 */
public class MailSendFailedException extends RuntimeException {

    public MailSendFailedException(Throwable cause) {
        super("메일 발송 실패: " + cause.getMessage(), cause);
    }
}
