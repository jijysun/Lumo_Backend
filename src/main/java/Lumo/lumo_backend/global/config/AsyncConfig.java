package Lumo.lumo_backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 메일 워커 전용 풀 (A-5 / H-7).
     *
     * <p><b>스레드 수는 이전과 같은 15개다.</b> 구 {@code mailExecutor}(core 15) 시절에도
     * 워커 15개가 core 스레드를 하나씩 차지해 정확히 15개였다 — 애초 의도대로였다.
     * 바뀐 것은 개수가 아니라 <b>풀의 계약</b>이다.
     *
     * <p>구 설정의 {@code max 50} · {@code queue 100} 은 워커 루프에는 성립할 수 없는 여유였다.
     * 워커는 반환되지 않으므로 큐에 들어간 작업은 <b>시작조차 못 한 채 조용히 대기</b>하고,
     * max 확장은 "큐 100 이 찬 뒤"라는 도달 불가능한 조건에 걸려 있었다.
     *
     * <p>그래서 {@code core = max = mail.worker.count}, {@code queue = 0} 으로 못 박는다.
     * 워커 수를 잘못 올려도 큐에 잠기지 않고 {@code TaskRejectedException} 으로 <b>기동 시점에 즉시 터진다</b> —
     * 조용히 적은 수로 도는 것보다 낫다.
     *
     * <p>⚠️ 워커 수 15 자체의 근거는 <b>아직 없다</b>(G-4). 이 풀은 그 숫자를 정당화하지 않는다.
     * 다만 {@code WorkerInitializer} 와 같은 프로퍼티를 보게 만들어, 값을 바꿔가며 실측할 수 있게 한 것이다
     * (이전에는 상수 15 가 두 파일에 각각 박혀 있어 한쪽만 고치면 조용히 어긋났다).
     */
    @Bean(name = "mailWorkerExecutor")
    public Executor mailWorkerExecutor(@Value("${mail.worker.count:15}") int workerCount) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workerCount);
        executor.setMaxPoolSize(workerCount);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("MailWorker-");
        // 워커는 running=false 를 보고 스스로 빠져나옴. BLOCK 2초보다 넉넉히
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * 회수분 재발송 전용 풀 (H-10).
     *
     * <p><b>mailWorkerExecutor 를 재사용할 수 없다.</b> 그 풀은 core=max=워커수 이고 큐가 0 인데,
     * WorkerInitializer 가 정확히 워커수만큼의 무한 루프를 던져 <b>전 스레드가 영구 점유</b>된다.
     * 큐가 0 이면 내부 큐가 SynchronousQueue 라 대기 자체가 없으므로, 여기에 회수 작업을 넣으면
     * 기다리는 게 아니라 <b>즉시 거절</b>된다. H-7 에서 떼어낸 문제를 되살리는 셈이다.
     *
     * <p>거절 정책은 {@code AbortPolicy}(기본값)를 명시적으로 둔다. {@code CallerRunsPolicy} 를 쓰면
     * 큐가 넘칠 때 <b>호출자인 스케줄러 스레드가 대신 실행</b>하게 되어 H-10 이 그대로 재발한다.
     *
     * <p>거절돼도 유실은 없다 — XACK 을 하지 않았으므로 항목이 PEL 에 남아 다음 회차에 다시 회수된다.
     * at-most-once(BRPOP) 에서 at-least-once(XREADGROUP+XACK) 로 계약을 바꾼 것의 실질적 이득이다.
     *
     * <p>큐 용량은 스케줄러의 SCAN_LIMIT 과 맞춘다. 한 회차분을 통째로 받아내지 못하면
     * XCLAIM 이 이미 올려놓은 delivery count 만 쌓여 <b>발송 시도 없이 DLQ 로 가는</b> 경로가 열린다.
     */
    @Bean(name = "mailRecoveryExecutor")
    public Executor mailRecoveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("MailRecovery-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean(name = "alarmTaskExecutor")
    public Executor alarmTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("alarm-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }

}