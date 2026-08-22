package Lumo.lumo_backend.domain.email.support;

/**
 * 메일 발송 실패의 성격 (A-6 / G-2).
 *
 * <p>모든 실패를 똑같이 재시도하는 것은 낭비이자 위험이다. 존재하지 않는 주소로 계속 재시도하면
 * <b>바운스율이 올라가 발신 도메인 평판이 훼손</b>되고, SES 같은 관리형 서비스에서는 계정 정지 사유가 된다.
 */
public enum MailFailure {

    /** 다시 시도하면 성공할 수 있다 — 타임아웃 · 커넥션 실패 · SMTP 4.x.x. PEL 에 남겨 회수시킨다. */
    TRANSIENT,

    /** 몇 번을 보내도 같은 이유로 실패한다 — 수신자 없음(5.1.1) · 거부(5.7.x) · 인코딩 오류. 즉시 DLQ. */
    PERMANENT;

    public boolean isPermanent() {
        return this == PERMANENT;
    }
}
