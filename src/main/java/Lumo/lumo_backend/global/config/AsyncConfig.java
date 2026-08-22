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

    @Bean (name = "mailExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(15);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100); // 큐가 꽉 차면 거절
        executor.setThreadNamePrefix("MailExecutor-");
        executor.setWaitForTasksToCompleteOnShutdown(true); // Graceful Shutdown set
        executor.setAwaitTerminationSeconds(5); // Graceful Shutdown set
        executor.initialize();
        return executor;
    }

    /**
     * 메일 워커 전용 풀
     *
     * <p>이전에는 워커가 {@code mailExecutor}(core 15)를 썼는데 <b>무한 루프 워커 15개가 core 를 전부 점유</b>했다.
     * ThreadPoolTaskExecutor 는 core 채움 → 큐 채움 → 그 다음에야 max 확장이므로, 다른 {@code @Async("mailExecutor")} 작업은 큐 100 이 꽉 찬 뒤에야 스레드를 얻는 구조였다.
     *
     * <p>워커 수만큼 고정하고 큐를 0 으로 둔다 — 이 풀에 들어오는 작업은 워커 루프뿐이고
     * 워커는 반환되지 않으므로 큐가 있어도 의미가 없다.
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