package Lumo.lumo_backend.domain.alarm.entity.service;

import Lumo.lumo_backend.domain.alarm.entity.*;
import Lumo.lumo_backend.domain.alarm.entity.dto.*;
import Lumo.lumo_backend.domain.alarm.entity.exception.AlarmException;
import Lumo.lumo_backend.domain.alarm.entity.exception.code.AlarmErrorCode;
import Lumo.lumo_backend.domain.alarm.entity.repository.*;
import Lumo.lumo_backend.domain.member.entity.Member;
import Lumo.lumo_backend.global.exception.GeneralException;
import Lumo.lumo_backend.global.apiResponse.status.ErrorCode;
import Lumo.lumo_backend.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlarmService {

    private final AlarmRepository alarmRepository;
    private final AlarmRepeatDayRepository repeatDayRepository;
    private final AlarmLogRepository alarmLogRepository;
    private final MissionContentRepository missionContentRepository;
    private final MissionHistoryRepository missionHistoryRepository;
    private final AlarmSoundService alarmSoundService;
    private final AlarmSnoozeRepository alarmSnoozeRepository;
    private final AlarmMissionRepository alarmMissionRepository;
    private final AlarmAsyncService alarmAsyncService;
    private final MemberRepository memberRepository; // (G-6) 클레임의 memberId 로 참조를 얻는다

    /**
     * 알람 생성
     */
    @Transactional
    public AlarmResponseDto createAlarm(Long memberId, AlarmCreateRequestDto requestDto) {
        // (G-6) 컨트롤러가 더 이상 Member 엔티티를 넘기지 않는다. 프록시는 SELECT 를 발생시키지 않으며,
        // 연관 설정과 FK 비교에는 식별자만 있으면 충분하다.
        Member member = memberRepository.getReferenceById(memberId);
        // 1. 알람 생성
        Alarm alarm = Alarm.builder()
                .member(member)
                .alarmTime(requestDto.getAlarmTime())
                .label(requestDto.getLabel())
                .isEnabled(requestDto.getIsEnabled())
                .soundType(requestDto.getSoundType())
                .vibration(requestDto.getVibration())
                .volume(requestDto.getVolume())
                .build();

        Alarm savedAlarm = alarmRepository.save(alarm);

        // 2. 스누즈 설정
        if (requestDto.getSnoozeSetting() != null) {
            AlarmSnooze snooze = AlarmSnooze.builder()
                    .alarm(savedAlarm)
                    .isEnabled(requestDto.getSnoozeSetting().getIsEnabled())
                    .intervalSec(requestDto.getSnoozeSetting().getIntervalSec())
                    .maxCount(requestDto.getSnoozeSetting().getMaxCount())
                    .build();
            alarmSnoozeRepository.save(snooze);
        }

        // 3. 미션 설정 저장
        if (requestDto.getMissionSetting() != null) {
            AlarmMission mission = AlarmMission.builder()
                    .alarm(savedAlarm)
                    .missionType(requestDto.getMissionSetting().getMissionType())
                    .difficulty(requestDto.getMissionSetting().getDifficulty())
                    .walkGoalMeter(requestDto.getMissionSetting().getWalkGoalMeter())
                    .questionCount(requestDto.getMissionSetting().getQuestionCount() != null
                            ? requestDto.getMissionSetting().getQuestionCount() : 1)
                    .build();
            alarmMissionRepository.save(mission);
        }

        // 4. 반복 요일 설정
        if (requestDto.getRepeatDays() != null && !requestDto.getRepeatDays().isEmpty()) {
            List<AlarmRepeatDay> repeatDays = requestDto.getRepeatDays().stream()
                    .map(dayOfWeek -> AlarmRepeatDay.builder()
                            .alarm(savedAlarm)
                            .dayOfWeek(dayOfWeek)
                            .build())
                    .collect(Collectors.toList());
            repeatDayRepository.saveAll(repeatDays);
        }

        // 5. 최종 조회하여 반환 (fetch join으로 조회)
        Alarm finalAlarm = alarmRepository.findByIdAndMemberWithDetails(savedAlarm.getAlarmId(), member)
                .orElseThrow(() -> new AlarmException(AlarmErrorCode.ALARM_NOT_FOUND));

        return AlarmResponseDto.from(finalAlarm);
    }

    /**
     * 내 알람 목록 조회
     */
    public List<AlarmResponseDto> getMyAlarms(Long memberId) {
        // (G-6) 컨트롤러가 더 이상 Member 엔티티를 넘기지 않는다. 프록시는 SELECT 를 발생시키지 않으며,
        // 연관 설정과 FK 비교에는 식별자만 있으면 충분하다.
        Member member = memberRepository.getReferenceById(memberId);
        List<Alarm> alarms = alarmRepository.findAllByMemberWithDetails(member);
        return alarms.stream()
                .map(AlarmResponseDto::from)
                .collect(Collectors.toList());
    }

    /**
     * 알람 상세 조회
     */
    public AlarmResponseDto getAlarmDetail(Long memberId, Long alarmId) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);
        return AlarmResponseDto.from(alarm);
    }


    /**
     * 알람 삭제
     */
    @Transactional
    public void deleteAlarm(Long memberId, Long alarmId) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);
        alarmRepository.delete(alarm);
    }

    /**
     * 알람 ON/OFF 토글
     */
    @Transactional
    public AlarmResponseDto toggleAlarm(Long memberId, Long alarmId) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);

        Alarm toggledAlarm = Alarm.builder()
                .alarmId(alarm.getAlarmId())
                .member(alarm.getMember())
                .alarmTime(alarm.getAlarmTime())
                .label(alarm.getLabel())
                .isEnabled(!alarm.getIsEnabled())
                .soundType(alarm.getSoundType())
                .vibration(alarm.getVibration())
                .volume(alarm.getVolume())
                .repeatDays(alarm.getRepeatDays())
                .alarmSnooze(alarm.getAlarmSnooze())
                .alarmMission(alarm.getAlarmMission())
                .build();

        Alarm savedAlarm = alarmRepository.save(toggledAlarm);
        return AlarmResponseDto.from(savedAlarm);
    }

    /**
     * 반복 요일 조회
     */
    public List<String> getRepeatDays(Long memberId, Long alarmId) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);
        return alarm.getRepeatDays().stream()
                .map(repeatDay -> repeatDay.getDayOfWeek().name())
                .collect(Collectors.toList());
    }

    /**
     * 반복 요일 설정
     */
    @Transactional
    public List<String> updateRepeatDays(Long memberId, Long alarmId, RepeatDaysUpdateRequestDto requestDto) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);

        // 기존 반복 요일 삭제
        repeatDayRepository.deleteByAlarm(alarm);

        // 새 반복 요일 추가
        List<AlarmRepeatDay> newRepeatDays = requestDto.getRepeatDays().stream()
                .map(dayOfWeek -> AlarmRepeatDay.builder()
                        .alarm(alarm)
                        .dayOfWeek(dayOfWeek)
                        .build())
                .collect(Collectors.toList());

        repeatDayRepository.saveAll(newRepeatDays);

        return newRepeatDays.stream()
                .map(repeatDay -> repeatDay.getDayOfWeek().name())
                .collect(Collectors.toList());
    }

    /**
     * 스누즈 설정 조회
     */
    public AlarmResponseDto.SnoozeSettingResponseDto getSnoozeSettings(Long memberId, Long alarmId) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);

        if (alarm.getAlarmSnooze() == null) {
            throw new AlarmException(AlarmErrorCode.ALARM_NOT_FOUND);
        }

        return AlarmResponseDto.SnoozeSettingResponseDto.builder()
                .snoozeId(alarm.getAlarmSnooze().getSnoozeId())
                .isEnabled(alarm.getAlarmSnooze().getIsEnabled())
                .intervalSec(alarm.getAlarmSnooze().getIntervalSec())
                .maxCount(alarm.getAlarmSnooze().getMaxCount())
                .build();
    }

    /**
     * 스누즈 설정 수정
     */
    @Transactional
    public AlarmResponseDto.SnoozeSettingResponseDto updateSnoozeSettings(
            Long memberId, Long alarmId, AlarmCreateRequestDto.SnoozeSettingDto requestDto) {

        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);

        AlarmSnooze snooze = alarm.getAlarmSnooze();
        if (snooze == null) {
            snooze = AlarmSnooze.builder()
                    .alarm(alarm)
                    .isEnabled(requestDto.getIsEnabled())
                    .intervalSec(requestDto.getIntervalSec())
                    .maxCount(requestDto.getMaxCount())
                    .build();
        } else {
            snooze = AlarmSnooze.builder()
                    .snoozeId(snooze.getSnoozeId())
                    .alarm(alarm)
                    .isEnabled(requestDto.getIsEnabled() != null ? requestDto.getIsEnabled() : snooze.getIsEnabled())
                    .intervalSec(requestDto.getIntervalSec() != null ? requestDto.getIntervalSec() : snooze.getIntervalSec())
                    .maxCount(requestDto.getMaxCount() != null ? requestDto.getMaxCount() : snooze.getMaxCount())
                    .build();
        }

        AlarmSnooze savedSnooze = alarmSnoozeRepository.save(snooze);

        return AlarmResponseDto.SnoozeSettingResponseDto.builder()
                .snoozeId(savedSnooze.getSnoozeId())
                .isEnabled(savedSnooze.getIsEnabled())
                .intervalSec(savedSnooze.getIntervalSec())
                .maxCount(savedSnooze.getMaxCount())
                .build();
    }

    /**
     * 스누즈 ON/OFF 토글
     */
    @Transactional
    public AlarmResponseDto.SnoozeSettingResponseDto toggleSnooze(Long memberId, Long alarmId) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);

        AlarmSnooze snooze = alarm.getAlarmSnooze();
        if (snooze == null) {
            throw new AlarmException(AlarmErrorCode.ALARM_NOT_FOUND);
        }

        AlarmSnooze toggledSnooze = AlarmSnooze.builder()
                .snoozeId(snooze.getSnoozeId())
                .alarm(alarm)
                .isEnabled(!snooze.getIsEnabled())
                .intervalSec(snooze.getIntervalSec())
                .maxCount(snooze.getMaxCount())
                .build();

        AlarmSnooze savedSnooze = alarmSnoozeRepository.save(toggledSnooze);

        return AlarmResponseDto.SnoozeSettingResponseDto.builder()
                .snoozeId(savedSnooze.getSnoozeId())
                .isEnabled(savedSnooze.getIsEnabled())
                .intervalSec(savedSnooze.getIntervalSec())
                .maxCount(savedSnooze.getMaxCount())
                .build();
    }

    /**
     * 미션 설정 조회
     */
    public MissionSettingDto getMissionSettings(Long memberId, Long alarmId) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);
        return MissionSettingDto.from(alarm.getAlarmMission());
    }

    /**
     * 미션 설정 수정
     */
    @Transactional
    public MissionSettingDto updateMissionSettings(Long memberId, Long alarmId, MissionSettingDto requestDto) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);

        AlarmMission mission = alarm.getAlarmMission();
        if (mission == null) {
            mission = AlarmMission.builder()
                    .alarm(alarm)
                    .missionType(requestDto.getMissionType())
                    .difficulty(requestDto.getDifficulty())
                    .walkGoalMeter(requestDto.getWalkGoalMeter())
                    .questionCount(requestDto.getQuestionCount() != null ? requestDto.getQuestionCount() : 1)
                    .build();
        } else {
            mission = AlarmMission.builder()
                    .missionId(mission.getMissionId())
                    .alarm(alarm)
                    .missionType(requestDto.getMissionType())
                    .difficulty(requestDto.getDifficulty())
                    .walkGoalMeter(requestDto.getWalkGoalMeter())
                    .questionCount(requestDto.getQuestionCount() != null ? requestDto.getQuestionCount() : 1)
                    .build();
        }

        AlarmMission savedMission = alarmMissionRepository.save(mission);
        return MissionSettingDto.from(savedMission);
    }

    /**
     * 미션 시작 (문제 발급)
     */
    @Transactional(readOnly = true)
    public List<MissionContentResponseDto> startMission(Long memberId, Long alarmId) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);
        AlarmMission mission = alarm.getAlarmMission();

        if (mission == null || mission.getMissionType() == MissionType.NONE) {
            throw new AlarmException(AlarmErrorCode.MISSION_NOT_FOUND);
        }

        // 걷기 미션은 문제가 없음
        if (mission.getMissionType() == MissionType.WALK) {
            return new ArrayList<>();
        }

        // DB에서 랜덤 문제 조회
        List<MissionContent> contents = missionContentRepository.findRandomByTypeAndDifficulty(
                mission.getMissionType().name(),
                mission.getDifficulty().name(),
                mission.getQuestionCount()
        );

        if (contents.isEmpty()) {
            throw new AlarmException(AlarmErrorCode.MISSION_CONTENT_NOT_FOUND);
        }

        // 정답 제외하고 반환
        return contents.stream()
                .map(MissionContentResponseDto::fromWithoutAnswer)
                .collect(Collectors.toList());
    }

    /**
     * 미션 답안 제출
     * - 미션 기록 저장은 동기, 통계 업데이트는 비동기 처리
     */
    @Transactional
    public MissionSubmitResponseDto submitMissionAnswer(Long memberId, Long alarmId, MissionSubmitDto requestDto) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);
        AlarmMission mission = alarm.getAlarmMission();

        if (mission == null) {
            throw new AlarmException(AlarmErrorCode.MISSION_NOT_FOUND);
        }

        // 문제 조회
        MissionContent content = missionContentRepository.findById(requestDto.getContentId())
                .orElseThrow(() -> new AlarmException(AlarmErrorCode.MISSION_CONTENT_NOT_FOUND));

        // 정답 확인
        boolean isCorrect = content.getAnswer().trim().equalsIgnoreCase(requestDto.getUserAnswer().trim());

        // 미션 기록 저장 (정답일 경우에만) - 동기 처리 (데이터 무결성)
        if (isCorrect) {
            MissionHistory history = MissionHistory.builder()
                    .alarm(alarm)
                    .missionType(mission.getMissionType())
                    .isSuccess(true)
                    .attemptCount(requestDto.getAttemptCount())
                    .completedAt(LocalDateTime.now())
                    .build();
            missionHistoryRepository.save(history);
        }

        return MissionSubmitResponseDto.builder()
                .isCorrect(isCorrect)
                .isCompleted(isCorrect)
                .remainingQuestions(isCorrect ? 0 : mission.getQuestionCount() - 1)
                .message(isCorrect ? "정답입니다!" : "틀렸습니다. 다시 시도해주세요.")
                .build();
    }

    /**
     * 걷기 미션 진행률 업데이트
     * - 미션 기록 저장은 동기, 통계 업데이트는 비동기 처리
     */
    @Transactional
    public WalkProgressResponseDto updateWalkProgress(Long memberId, Long alarmId, WalkProgressDto requestDto) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);
        AlarmMission mission = alarm.getAlarmMission();

        if (mission == null || mission.getMissionType() != MissionType.WALK) {
            throw new AlarmException(AlarmErrorCode.MISSION_NOT_FOUND);
        }

        // 진행률 계산
        WalkProgressResponseDto response = WalkProgressDto.toResponse(
                requestDto.getCurrentDistance(),
                mission.getWalkGoalMeter()
        );

        // 미션 완료 시 기록 저장 - 동기 처리 (데이터 무결성)
        if (response.getIsCompleted()) {
            MissionHistory history = MissionHistory.builder()
                    .alarm(alarm)
                    .missionType(MissionType.WALK)
                    .isSuccess(true)
                    .attemptCount(1)
                    .completedAt(LocalDateTime.now())
                    .build();
            missionHistoryRepository.save(history);
        }

        return response;
    }

    /**
     * 내 미션 수행 기록 조회
     */
    public List<MissionHistoryResponseDto> getMyMissionHistory(Long memberId) {
        List<MissionHistory> histories = missionHistoryRepository.findByAlarm_Member_IdOrderByCompletedAtDesc(memberId);
        return histories.stream()
                .map(MissionHistoryResponseDto::from)
                .collect(Collectors.toList());
    }

    /**
     * 특정 알람의 미션 기록 조회
     */
    public List<MissionHistoryResponseDto> getAlarmMissionHistory(Long memberId, Long alarmId) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);
        List<MissionHistory> histories = missionHistoryRepository.findByAlarmOrderByCompletedAtDesc(alarm);
        return histories.stream()
                .map(MissionHistoryResponseDto::from)
                .collect(Collectors.toList());
    }

    /**
     * 내 알람 울림 기록 조회
     */
    public List<AlarmLogResponseDto> getMyAlarmLogs(Long memberId) {
        List<AlarmLog> logs = alarmLogRepository.findByAlarm_Member_IdOrderByTriggeredAtDesc(memberId);
        return logs.stream()
                .map(AlarmLogResponseDto::from)
                .collect(Collectors.toList());
    }

    /**
     * 특정 알람의 울림 기록 조회
     */
    public List<AlarmLogResponseDto> getAlarmLogs(Long memberId, Long alarmId) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);
        List<AlarmLog> logs = alarmLogRepository.findByAlarmOrderByTriggeredAtDesc(alarm);
        return logs.stream()
                .map(AlarmLogResponseDto::from)
                .collect(Collectors.toList());
    }

    /**
     * 알람 울림 기록 (트리거)
     */
    @Transactional
    public AlarmLogResponseDto triggerAlarm(Long memberId, Long alarmId) {
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);

        AlarmLog log = AlarmLog.builder()
                .alarm(alarm)
                .triggeredAt(LocalDateTime.now())
                .snoozeCount(0)
                .build();

        AlarmLog savedLog = alarmLogRepository.save(log);
        return AlarmLogResponseDto.from(savedLog);
    }

    /**
     * 알람 해제 기록
     * - 로그 저장은 동기, 통계 업데이트는 비동기 처리
     */
    @Transactional
    public AlarmLogResponseDto dismissAlarm(Long memberId, Long alarmId, AlarmDismissRequestDto requestDto) {
        // 1. 알람 조회
        Alarm alarm = findAlarmByIdAndMember(alarmId, memberId);

        // 2. 최근 울림 기록 찾기
        List<AlarmLog> recentLogs = alarmLogRepository.findByAlarmOrderByTriggeredAtDesc(alarm);

        // 빈 리스트 체크
        if (recentLogs.isEmpty()) {
            throw new AlarmException(AlarmErrorCode.ALARM_LOG_NOT_FOUND);
        }

        AlarmLog recentLog = recentLogs.get(0);

        // 3. AlarmLog 업데이트 - 동기 처리 (데이터 무결성)
        AlarmLog updatedLog = AlarmLog.builder()
                .logId(recentLog.getLogId())
                .alarm(alarm)
                .triggeredAt(recentLog.getTriggeredAt())
                .dismissedAt(LocalDateTime.now())
                .dismissType(requestDto.getDismissType())
                .snoozeCount(requestDto.getSnoozeCount())
                .build();

        AlarmLog savedLog = alarmLogRepository.save(updatedLog);

        // 4. 미션으로 해제한 경우 통계 비동기 업데이트
        if (requestDto.getDismissType() == DismissType.MISSION) {
            alarmAsyncService.updateMemberStatisticsAsync(memberId);
        }

        return AlarmLogResponseDto.from(savedLog);
    }

    /**
     * 내 통계 조회
     * - CompletableFuture로 미션 통계와 알람 통계를 병렬 조회
     */
    public MemberStatisticsResponseDto getMyStatistics(Long memberId) {
        /*
         * (G-6 예외) 여기만 프록시로는 안 된다.
         * 반환 DTO(MemberStatisticsResponseDto)가 missionSuccessRate·consecutiveSuccessCnt 를 읽는데,
         * 이 값들은 자주 바뀌므로 JWT 클레임에 담을 수 없다. 게다가 아래 CompletableFuture 가
         * 다른 스레드에서 도므로 프록시를 넘기면 LazyInitializationException 이 난다.
         * 통계 API 는 DB 접근이 원래 필연이다.
         */
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new GeneralException(ErrorCode.AUTH_UNAUTHORIZED));
        LocalDateTime monthStart = LocalDateTime.now()
                .withDayOfMonth(1)
                .withHour(0).withMinute(0).withSecond(0).withNano(0);

        // 미션 기록과 알람 로그를 병렬로 조회
        CompletableFuture<List<MissionHistory>> missionFuture = CompletableFuture.supplyAsync(() ->
                missionHistoryRepository.findByAlarm_Member_IdOrderByCompletedAtDesc(memberId)
        );

        CompletableFuture<Integer> triggerCountFuture = CompletableFuture.supplyAsync(() ->
                (int) alarmLogRepository.findByAlarm_Member_IdOrderByTriggeredAtDesc(memberId)
                        .stream()
                        .filter(log -> log.getTriggeredAt().isAfter(monthStart))
                        .count()
        );

        // 두 작업 모두 완료될 때까지 대기
        CompletableFuture.allOf(missionFuture, triggerCountFuture).join();

        List<MissionHistory> missionHistories = missionFuture.join();

        int totalAttempts = (int) missionHistories.stream()
                .filter(mh -> mh.getCompletedAt().isAfter(monthStart))
                .count();

        int totalSuccess = (int) missionHistories.stream()
                .filter(mh -> mh.getCompletedAt().isAfter(monthStart) && mh.getIsSuccess())
                .count();

        int totalTriggered = triggerCountFuture.join();

        return MemberStatisticsResponseDto.from(member, totalAttempts, totalSuccess, totalTriggered);
    }

    /**
     * 사운드 타입 유효성 검증 및 기본값 처리
     */
    private String validateAndGetSoundType(String soundType) {
        if (soundType == null || soundType.trim().isEmpty()) {
            return alarmSoundService.getDefaultSoundType();
        }

        if (!alarmSoundService.isValidSoundType(soundType)) {
            log.warn("Invalid sound type provided: {}, using default", soundType);
            return alarmSoundService.getDefaultSoundType();
        }

        return soundType.toUpperCase();
    }

    /**
     * 알람 조회 헬퍼 메서드
     */
    private Alarm findAlarmByIdAndMember(Long alarmId, Long memberId) {
        // (G-6) 컨트롤러가 더 이상 Member 엔티티를 넘기지 않는다. 프록시는 SELECT 를 발생시키지 않으며,
        // 연관 설정과 FK 비교에는 식별자만 있으면 충분하다.
        Member member = memberRepository.getReferenceById(memberId);
        return alarmRepository.findByIdAndMemberWithDetails(alarmId, member)
                .orElseThrow(() -> new AlarmException(AlarmErrorCode.ALARM_NOT_FOUND));
    }
}
