package Lumo.lumo_backend.global.redis;

/**
 * 메일 발송 스트림의 이름·필드·컨슈머 규칙 (A-5 / G-1).
 *
 * <p>이전 구조(Redis List + {@code BRPOP})는 <b>at-most-once</b> 였다. 꺼내는 순간 Redis 에서
 * 사라지므로 그 직후 워커가 죽으면 작업이 영구 소실되고, 사용자는 인증 메일을 영영 받지 못한다.
 * Streams + Consumer Group 은 읽어도 PEL(Pending Entries List)에 남아 있다가
 * {@code XACK} 해야 비로소 빠지므로 <b>at-least-once</b> 가 된다.
 *
 * <p>이름 조립이 여러 파일에 흩어지면 한 곳만 고쳐도 조용히 어긋나므로 여기로 모은다
 * (H-9 의 {@code RefreshTokenKey} 와 같은 이유).
 */
public final class MailStream {

    /** 스트림 키. 구 List 키(email_queue)와 이름을 달리해 전환 중 섞이지 않게 한다. */
    public static final String KEY = "mail:stream";

    /** 컨슈머 그룹. 워커 전원이 이 그룹에 속해 작업을 나눠 갖는다. */
    public static final String GROUP = "mailers";

    /*
     * 이전에는 "email:code" 한 문자열을 split(":") 로 쪼갰다.
     * 이메일에 ':' 가 들어가면 그대로 깨지는 구조였는데, Streams 는 필드 맵이라 파싱 자체가 사라진다.
     */
    public static final String FIELD_EMAIL = "email";
    public static final String FIELD_CODE = "code";

    /**
     * 보관 상한. <b>{@code XACK} 은 삭제가 아니다</b> — PEL 에서만 빠지고 엔트리는 스트림에 남는다.
     * 트리밍하지 않으면 메모리가 단조 증가한다. {@code ~} 근사 트리밍이 정확 트리밍보다 훨씬 싸다.
     */
    public static final long MAX_LEN = 10_000L;

    /** 이 시간 이상 미확인 상태로 묵은 항목만 회수 대상으로 본다. 짧으면 정상 처리 중인 걸 뺏는다. */
    public static final long RECLAIM_IDLE_SECONDS = 60L;

    /** 전달 횟수가 이 값을 넘으면 재시도를 포기한다 (A-6 에서 DLQ 로 연결). */
    public static final long MAX_DELIVERY_COUNT = 3L;

    /** 회수 스케줄러가 소유권을 가져올 때 쓰는 컨슈머 이름. */
    public static final String RECOVERY_CONSUMER = "recovery";

    private MailStream() {
    }

    /**
     * 워커 컨슈머 이름은 <b>인덱스로 고정</b>한다.
     * 매번 UUID 같은 걸 쓰면 재기동마다 죽은 컨슈머가 그룹에 쌓인다.
     */
    public static String consumerName(int workerId) {
        return "worker-" + workerId;
    }
}
