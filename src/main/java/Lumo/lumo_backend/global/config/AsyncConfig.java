package Lumo.lumo_backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

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