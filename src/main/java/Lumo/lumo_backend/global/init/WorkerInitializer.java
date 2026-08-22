package Lumo.lumo_backend.global.init;

import Lumo.lumo_backend.domain.email.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkerInitializer implements ApplicationRunner {
    private final EmailService emailService;

    /**
     * 워커 수. G-4("왜 15개인가")를 실측으로 판정하려면 재빌드 없이 바꿀 수 있어야 한다.
     * mailWorkerExecutor 의 풀 크기와 반드시 같은 값을 본다.
     */
    @Value("${mail.worker.count:15}")
    private int workerCount;

    @Override
    public void run(ApplicationArguments args) {
        for (int i = 0; i < workerCount; i++) {
            emailService.startMailWorker(i);
        }
    }
}