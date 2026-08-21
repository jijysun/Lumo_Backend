package Lumo.lumo_backend.domain.alarm.entity.service;

import Lumo.lumo_backend.domain.alarm.entity.MissionHistory;
import Lumo.lumo_backend.domain.alarm.entity.repository.AlarmLogRepository;
import Lumo.lumo_backend.domain.alarm.entity.repository.MissionHistoryRepository;
import Lumo.lumo_backend.domain.member.entity.Member;
import Lumo.lumo_backend.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 비동기 처리 전용 서비스
 * -업데이트 등 응답 속도에 영향을 주지 않아야 하는 작업 처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmAsyncService {

    private final MissionHistoryRepository missionHistoryRepository;
    private final AlarmLogRepository alarmLogRepository;
    private final MemberRepository memberRepository; // (G-6) 클레임의 memberId 로 참조를 얻는다

    /**
     *비동기 업데이트
     */
    @Async("alarmTaskExecutor")
    @Transactional
    public void updateMemberStatisticsAsync(Long memberId) {
        try {
            /*
             * (G-6) 이전에는 Member 엔티티를 그대로 받았다. 그런데 이 메서드는 @Async 라
             * 호출자와 다른 스레드·다른 트랜잭션에서 실행되고, 넘어온 엔티티는 그 트랜잭션에서
             * 준영속(detached) 상태다. 아래 incrementConsecutiveSuccessCnt() /
             * updateMissionSuccessRate() 는 더티체킹에 의존하므로 실제로는 DB 에 반영되지
             * 않았을 가능성이 높다. (M-5 의 주간 배치와 같은 유형의 결함)
             * 이 트랜잭션 안에서 다시 조회해 영속 상태로 만든다.
             */
            Member member = memberRepository.findById(memberId).orElse(null);
            if (member == null) {
                log.warn("비동기 업데이트 대상 회원 없음 - memberId: {}", memberId);
                return;
            }
            log.debug("비동기 업데이트 시작 - memberId: {}", member.getId());

            LocalDateTime monthStart = LocalDateTime.now()
                    .withDayOfMonth(1)
                    .withHour(0).withMinute(0).withSecond(0).withNano(0);

            int totalAttempts = (int) missionHistoryRepository
                    .findByAlarm_Member_IdOrderByCompletedAtDesc(member.getId())
                    .stream()
                    .filter(mh -> mh.getCompletedAt().isAfter(monthStart))
                    .count();

            int totalSuccess = (int) missionHistoryRepository
                    .findByAlarm_Member_IdOrderByCompletedAtDesc(member.getId())
                    .stream()
                    .filter(mh -> mh.getCompletedAt().isAfter(monthStart) && mh.getIsSuccess())
                    .count();

            LocalDateTime todayStart = LocalDateTime.now()
                    .withHour(0).withMinute(0).withSecond(0).withNano(0);

            boolean todaySuccess = missionHistoryRepository
                    .findByAlarm_Member_IdOrderByCompletedAtDesc(member.getId())
                    .stream()
                    .anyMatch(mh -> mh.getCompletedAt().isAfter(todayStart) && mh.getIsSuccess());

            if (todaySuccess) {
                member.incrementConsecutiveSuccessCnt();
            }

            member.updateMissionSuccessRate(totalSuccess, totalAttempts);

            log.debug("비동기업데이트 완료 - memberId: {}, 시도: {}, 성공: {}",
                    member.getId(), totalAttempts, totalSuccess);

        } catch (Exception e) {
            log.error("비동기업데이트 실패 - memberId: {}", memberId, e);
        }
    }
}
