package Lumo.lumo_backend.global.redis;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 컨슈머 그룹을 기동 시 준비한다 (A-5 / G-1).
 *
 * <p>{@code XREADGROUP} 은 그룹이 없으면 NOGROUP 오류를 낸다. 워커가 뜨기 전에 그룹이 있어야 하므로
 * 초기화를 별도 빈으로 분리했다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailStreamInitializer {

    private final RedisTemplate<String, String> redisTemplate;

    @PostConstruct
    public void createGroupIfAbsent() {
        try {
            /*
             * ReadOffset.from("0") = 스트림 처음부터 소비한다.
             * "$"(최신)로 잡으면 그룹 생성 이전에 쌓여 있던 항목을 통째로 건너뛴다.
             *
             * Spring 의 createGroup 은 MKSTREAM 을 붙여주므로 스트림이 없어도 함께 만들어진다.
             */
            redisTemplate.opsForStream().createGroup(MailStream.KEY, ReadOffset.from("0"), MailStream.GROUP);
            log.info("[MailStreamInitializer] consumer group '{}' created on '{}'",
                    MailStream.GROUP, MailStream.KEY);

        } catch (Exception e) {
            /*
             * 재기동마다 BUSYGROUP 이 나는 것이 정상이다. 이걸 예외로 흘리면 앱이 뜨지 않는다.
             * 그 외 오류(연결 실패 등)는 그대로 올려 기동을 막는 편이 낫다 — 그룹 없이 뜨면
             * 워커 전원이 NOGROUP 으로 무한 재시도하며 조용히 아무 일도 안 한다.
             */
            if (isAlreadyExists(e)) {
                log.info("[MailStreamInitializer] consumer group '{}' already exists", MailStream.GROUP);
                return;
            }
            throw e;
        }
    }

    /**
     * 워커가 NOGROUP 을 만났을 때 호출한다 (자가 복구).
     *
     * <p>스트림이 지워지면(운영 실수 · <b>측정 회차 간 FLUSHDB</b> · 전량 트리밍) 그룹도 함께 사라진다.
     * 그때 기동 시 1회 생성만으로는 복구되지 않아 <b>워커 전원이 NOGROUP 으로 무한 재시도</b>하며
     * 조용히 아무 일도 하지 않는다. 실제로 로컬 검증에서 331회 반복이 관측됐다.
     */
    public boolean recreateIfMissing(Throwable cause) {
        if (!isNoGroup(cause)) {
            return false;
        }
        log.warn("[MailStreamInitializer] consumer group missing - recreating");
        createGroupIfAbsent();
        return true;
    }

    private boolean isNoGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("NOGROUP")) {
                return true;
            }
        }
        return false;
    }

    private boolean isAlreadyExists(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }
}
