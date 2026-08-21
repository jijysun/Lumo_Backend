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
package Lumo.lumo_backend.global.init;

import Lumo.lumo_backend.domain.email.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerInitializer implements ApplicationRunner {
    private final EmailService emailService;

    @Override
    public void run(ApplicationArguments args) {
        // (measure/s1-raw) 원시 구조에는 워커가 없다. 큐가 없으므로 기동할 대상도 없다.
        log.info("[WorkerInitializer] measure/s1-raw - 원시 구조: 메일 워커를 기동하지 않는다");
    }
}