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
package Lumo.lumo_backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * (measure/s1-raw) 원시 구조 재현용.
     *
     * 당시 코드는 한정자 없는 {@code @Async} 였다. 이 프로젝트에는 TaskExecutor 빈이 둘
     * (mailExecutor · alarmTaskExecutor) 있고 이름이 taskExecutor 인 빈은 없어서, Spring 은
     * 유일한 빈을 찾지 못하고 {@link SimpleAsyncTaskExecutor} 로 폴백한다 —
     * <b>요청마다 새 스레드를 만들고 재사용하지 않는</b> 실행기다.
     *
     * 폴백에 의존하면 Spring 버전에 따라 거동이 달라져 측정이 재현되지 않으므로,
     * 그때 실제로 쓰이던 것을 명시적으로 선언해 고정한다.
     */
    @Bean(name = "rawMailExecutor")
    public Executor rawMailExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("RawMail-");
        return executor;
    }


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