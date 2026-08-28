package Lumo.lumo_backend.domain.alarm.entity.controller;

import Lumo.lumo_backend.domain.alarm.entity.dto.*;
import Lumo.lumo_backend.domain.alarm.entity.service.AlarmSoundService;
import Lumo.lumo_backend.domain.alarm.entity.status.AlarmSuccessCode;
import Lumo.lumo_backend.domain.alarm.entity.service.AlarmService;
import Lumo.lumo_backend.global.apiResponse.APIResponse;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetails;
// import io.swagger.v3.oas.annotations.Operation;
// import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
// @Tag(name = "알람 API")
public class AlarmController {

    private final AlarmService alarmService;
    private final AlarmSoundService alarmSoundService;

    /**
     * 사용 가능한 알람 사운드 목록 조회
     * GET /api/alarms/sounds
     */
    @GetMapping("/sounds")
    // @Operation(summary = "알람 사운드 목록 조회 API", description = "클라이언트에서 사용 가능한 알람 사운드 식별자 목록을 조회하는 API입니다.")
    public APIResponse<List<AlarmSoundResponseDto>> getAvailableSounds() {
        List<AlarmSoundResponseDto> response = alarmSoundService.getAvailableSounds();
        return APIResponse.onSuccess(response, AlarmSuccessCode.ALARM_SOUNDS_RETRIEVED);
    }

    /**
     * 알람 생성
     * POST /api/alarms
     */
    @PostMapping
    // @Operation(summary = "알람 생성 API", description = "새로운 알람을 생성하는 API입니다. soundType에는 사운드 식별자를 전달해주세요.")
    public APIResponse<AlarmResponseDto> createAlarm(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody AlarmCreateRequestDto requestDto
    ) {
        AlarmResponseDto response = alarmService.createAlarm(userDetails.getMemberId(), requestDto);
        return APIResponse.onSuccess(response, AlarmSuccessCode.ALARM_CREATED);
    }

    /**
     * 내 알람 목록 조회
     * GET /api/alarms
     */
    @GetMapping
    // @Operation(summary = "내 알람 목록 조회 API", description = "사용자가 생성한 모든 알람을 조회하는 API입니다. JWT 인증 값을 RequestHeader에 포함해주세요.")
    public APIResponse<List<AlarmResponseDto>> getMyAlarms(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        List<AlarmResponseDto> response = alarmService.getMyAlarms(userDetails.getMemberId());
        return APIResponse.onSuccess(response, AlarmSuccessCode.ALARM_LIST_RETRIEVED);
    }

    /**
     * 알람 상세 조회
     * GET /api/alarms/{alarmId}
     */
    @GetMapping("/{alarmId}")
    // @Operation(summary = "알람 상세 조회 API", description = "특정 알람의 상세 정보를 조회하는 API입니다. 반복 요일, 스누즈 설정, 미션 설정을 포함합니다.")
    public APIResponse<AlarmResponseDto> getAlarmDetail(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId
    ) {
        AlarmResponseDto response = alarmService.getAlarmDetail(userDetails.getMemberId(), alarmId);
        return APIResponse.onSuccess(response, AlarmSuccessCode.ALARM_RETRIEVED);
    }



    /**
     * 알람 삭제
     * DELETE /api/alarms/{alarmId}
     */
    @DeleteMapping("/{alarmId}")
    // @Operation(summary = "알람 삭제 API", description = "특정 알람을 삭제하는 API입니다. 연관된 반복 요일, 스누즈, 미션 설정도 함께 삭제됩니다.")
    public APIResponse<Void> deleteAlarm(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId
    ) {
        alarmService.deleteAlarm(userDetails.getMemberId(), alarmId);
        return APIResponse.onSuccess(null, AlarmSuccessCode.ALARM_DELETED);
    }

    /**
     * 알람 ON/OFF 토글
     * PATCH /api/alarms/{alarmId}/toggle
     */
    @PatchMapping("/{alarmId}/toggle")
    // @Operation(summary = "알람 ON/OFF 토글 API", description = "알람의 활성화 상태를 토글하는 API입니다. ON 상태면 OFF로, OFF 상태면 ON으로 변경됩니다.")
    public APIResponse<AlarmResponseDto> toggleAlarm(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId
    ) {
        AlarmResponseDto response = alarmService.toggleAlarm(userDetails.getMemberId(), alarmId);
        return APIResponse.onSuccess(response, AlarmSuccessCode.ALARM_TOGGLED);
    }

    /**
     * 반복 요일 조회
     * GET /api/alarms/{alarmId}/repeat-days
     */
    @GetMapping("/{alarmId}/repeat-days")
    // @Operation(summary = "반복 요일 조회 API", description = "특정 알람의 반복 요일을 조회하는 API입니다. 월~일 중 설정된 요일 목록을 반환합니다.")
    public APIResponse<List<String>> getRepeatDays(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId
    ) {
        List<String> response = alarmService.getRepeatDays(userDetails.getMemberId(), alarmId);
        return APIResponse.onSuccess(response, AlarmSuccessCode.REPEAT_DAYS_RETRIEVED);
    }

    /**
     * 반복 요일 설정 (전체 교체)
     * PUT /api/alarms/{alarmId}/repeat-days
     */
    @PutMapping("/{alarmId}/repeat-days")
    // @Operation(summary = "반복 요일 설정 API", description = "알람의 반복 요일을 설정하는 API입니다. 기존 반복 요일은 모두 삭제되고 새로운 요일로 대체됩니다.")
    public APIResponse<List<String>> updateRepeatDays(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId,
            @Valid @RequestBody RepeatDaysUpdateRequestDto requestDto
    ) {
        List<String> response = alarmService.updateRepeatDays(userDetails.getMemberId(), alarmId, requestDto);
        return APIResponse.onSuccess(response, AlarmSuccessCode.REPEAT_DAYS_UPDATED);
    }

    /**
     * 스누즈 설정 조회
     * GET /api/alarms/{alarmId}/snooze
     */
    @GetMapping("/{alarmId}/snooze")
    // @Operation(summary = "스누즈 설정 조회 API", description = "특정 알람의 스누즈 설정을 조회하는 API입니다. 스누즈 간격, 최대 횟수, 활성화 여부를 포함합니다.")
    public APIResponse<AlarmResponseDto.SnoozeSettingResponseDto> getSnoozeSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId
    ) {
        AlarmResponseDto.SnoozeSettingResponseDto response =
                alarmService.getSnoozeSettings(userDetails.getMemberId(), alarmId);
        return APIResponse.onSuccess(response, AlarmSuccessCode.SNOOZE_RETRIEVED);
    }

    /**
     * 스누즈 설정 수정 (간격, 횟수)
     * PUT /api/alarms/{alarmId}/snooze
     */
    @PutMapping("/{alarmId}/snooze")
    // @Operation(summary = "스누즈 설정 수정 API", description = "알람의 스누즈 간격(초), 최대 반복 횟수를 수정하는 API입니다.")
    public APIResponse<AlarmResponseDto.SnoozeSettingResponseDto> updateSnoozeSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId,
            @Valid @RequestBody AlarmCreateRequestDto.SnoozeSettingDto requestDto
    ) {
        AlarmResponseDto.SnoozeSettingResponseDto response =
                alarmService.updateSnoozeSettings(userDetails.getMemberId(), alarmId, requestDto);
        return APIResponse.onSuccess(response, AlarmSuccessCode.SNOOZE_UPDATED);
    }

    /**
     * 스누즈 ON/OFF 토글
     * PATCH /api/alarms/{alarmId}/snooze/toggle
     */
    @PatchMapping("/{alarmId}/snooze/toggle")
    // @Operation(summary = "스누즈 ON/OFF 토글 API", description = "알람의 스누즈 기능을 활성화/비활성화하는 API입니다.")
    public APIResponse<AlarmResponseDto.SnoozeSettingResponseDto> toggleSnooze(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId
    ) {
        AlarmResponseDto.SnoozeSettingResponseDto response =
                alarmService.toggleSnooze(userDetails.getMemberId(), alarmId);
        return APIResponse.onSuccess(response, AlarmSuccessCode.SNOOZE_TOGGLED);
    }

    /**
     * 미션 설정 조회
     * GET /api/alarms/{alarmId}/mission
     */
    @GetMapping("/{alarmId}/mission")
    // @Operation(summary = "미션 설정 조회 API", description = "특정 알람의 미션 설정을 조회하는 API입니다. 미션 타입, 난이도, 걷기 목표 거리, 문제 개수를 포함합니다.")
    public APIResponse<MissionSettingDto> getMissionSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId
    ) {
        MissionSettingDto response = alarmService.getMissionSettings(userDetails.getMemberId(), alarmId);
        return APIResponse.onSuccess(response, AlarmSuccessCode.MISSION_RETRIEVED);
    }

    /**
     * 미션 설정 수정 (유형, 난이도, 거리 등)
     * PUT /api/alarms/{alarmId}/mission
     */
    @PutMapping("/{alarmId}/mission")
    // @Operation(summary = "미션 설정 수정 API", description = "알람의 미션 설정을 수정하는 API입니다. 미션 타입(수학/OX퀴즈/타이핑/걷기), 난이도, 목표 거리 등을 설정할 수 있습니다.")
    public APIResponse<MissionSettingDto> updateMissionSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId,
            @Valid @RequestBody MissionSettingDto requestDto
    ) {
        MissionSettingDto response =
                alarmService.updateMissionSettings(userDetails.getMemberId(), alarmId, requestDto);
        return APIResponse.onSuccess(response, AlarmSuccessCode.MISSION_UPDATED);
    }

    /**
     * 미션 시작 (문제 발급)
     * POST /api/alarms/{alarmId}/missions/start
     */
    @PostMapping("/{alarmId}/missions/start")
    // @Operation(summary = "미션 시작 API", description = "알람 미션을 시작하고 문제를 발급하는 API입니다. 설정된 난이도와 개수에 맞는 문제가 랜덤으로 제공됩니다. 걷기 미션의 경우 빈 배열을 반환합니다.")
    public APIResponse<List<MissionContentResponseDto>> startMission(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId
    ) {
        List<MissionContentResponseDto> response =
                alarmService.startMission(userDetails.getMemberId(), alarmId);
        return APIResponse.onSuccess(response, AlarmSuccessCode.MISSION_STARTED);
    }

    /**
     * 미션 답안 제출 (정답 확인)
     * POST /api/alarms/{alarmId}/missions/submit
     */
    @PostMapping("/{alarmId}/missions/submit")
    // @Operation(summary = "미션 답안 제출 API", description = "미션 문제의 답안을 제출하고 정답 여부를 확인하는 API입니다. 정답이면 미션 완료 기록이 저장됩니다.")
    public APIResponse<MissionSubmitResponseDto> submitMissionAnswer(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId,
            @Valid @RequestBody MissionSubmitDto requestDto
    ) {
        MissionSubmitResponseDto response =
                alarmService.submitMissionAnswer(userDetails.getMemberId(), alarmId, requestDto);
        return APIResponse.onSuccess(response, AlarmSuccessCode.MISSION_SUBMITTED);
    }

    /**
     * 걷기 미션 거리 업데이트
     * POST /api/alarms/{alarmId}/missions/walk
     */
    @PostMapping("/{alarmId}/missions/walk")
    // @Operation(summary = "걷기 미션 거리 업데이트 API", description = "걷기 미션의 현재 진행 거리를 업데이트하는 API입니다. 목표 거리 달성 시 미션 완료 기록이 저장됩니다.")
    public APIResponse<WalkProgressResponseDto> updateWalkProgress(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId,
            @Valid @RequestBody WalkProgressDto requestDto
    ) {
        WalkProgressResponseDto response =
                alarmService.updateWalkProgress(userDetails.getMemberId(), alarmId, requestDto);
        return APIResponse.onSuccess(response, AlarmSuccessCode.MISSION_WALK_UPDATED);
    }


    /**
     * 내 미션 수행 기록 조회
     * GET /api/members/me/mission-history
     */
    @GetMapping("/members/me/mission-history")
    // @Operation(summary = "내 미션 수행 기록 조회 API", description = "사용자의 모든 미션 수행 기록을 조회하는 API입니다. 미션 타입, 성공 여부, 시도 횟수, 완료 시간을 포함합니다.")
    public APIResponse<List<MissionHistoryResponseDto>> getMyMissionHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        List<MissionHistoryResponseDto> response =
                alarmService.getMyMissionHistory(userDetails.getMemberId());
        return APIResponse.onSuccess(response, AlarmSuccessCode.MISSION_HISTORY_RETRIEVED);
    }

    /**
     * 내 알람 울림 기록 조회
     * GET /api/members/me/alarm-logs
     */
    @GetMapping("/members/me/alarm-logs")
    // @Operation(summary = "내 알람 울림 기록 조회 API", description = "사용자의 모든 알람 울림 기록을 조회하는 API입니다. 울린 시간, 해제 시간, 해제 방식, 스누즈 횟수를 포함합니다.")
    public APIResponse<List<AlarmLogResponseDto>> getMyAlarmLogs(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        List<AlarmLogResponseDto> response = alarmService.getMyAlarmLogs(userDetails.getMemberId());
        return APIResponse.onSuccess(response, AlarmSuccessCode.ALARM_LOG_RETRIEVED);
    }

    /**
     * 특정 알람의 울림 기록 조회
     * GET /api/alarms/{alarmId}/logs
     */
    @GetMapping("/{alarmId}/logs")
    // @Operation(summary = "특정 알람의 울림 기록 조회 API", description = "특정 알람의 울림 기록을 조회하는 API입니다.")
    public APIResponse<List<AlarmLogResponseDto>> getAlarmLogs(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId
    ) {
        List<AlarmLogResponseDto> response =
                alarmService.getAlarmLogs(userDetails.getMemberId(), alarmId);
        return APIResponse.onSuccess(response, AlarmSuccessCode.ALARM_LOG_RETRIEVED);
    }

    /**
     * 알람 울림 기록 (웹에서 호출)
     * POST /api/alarms/{alarmId}/trigger
     */
    @PostMapping("/{alarmId}/trigger")
    // @Operation(summary = "알람 울림 기록 API", description = "알람이 울렸을 때 호출하는 API입니다. 알람 울림 시각과 스누즈 카운트를 기록합니다.")
    public APIResponse<AlarmLogResponseDto> triggerAlarm(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId
    ) {
        AlarmLogResponseDto response = alarmService.triggerAlarm(userDetails.getMemberId(), alarmId);
        return APIResponse.onSuccess(response, AlarmSuccessCode.ALARM_TRIGGERED);
    }

    /**
     * 알람 해제 기록
     * POST /api/alarms/{alarmId}/dismiss
     */
    @PostMapping("/{alarmId}/dismiss")
    // @Operation(summary = "알람 해제 기록 API", description = "알람을 해제했을 때 호출하는 API입니다. 해제 방식(미션/스누즈/수동)과 스누즈 횟수를 기록합니다.")
    public APIResponse<AlarmLogResponseDto> dismissAlarm(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long alarmId,
            @Valid @RequestBody AlarmDismissRequestDto requestDto
    ) {
        AlarmLogResponseDto response = alarmService.dismissAlarm(userDetails.getMemberId(), alarmId, requestDto);
        return APIResponse.onSuccess(response, AlarmSuccessCode.ALARM_DISMISSED);
    }

    /**
     * 내 통계 조회
     * GET /api/members/me/statistics
     */
    @GetMapping("/members/me/statistics")
    // @Operation(summary = "내 통계 조회 API", description = "이번 달 미션 달성률, 연속 성공 일수, 알람 울림 횟수 등을 조회하는 API입니다.")
    public APIResponse<MemberStatisticsResponseDto> getMyStatistics(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        MemberStatisticsResponseDto response = alarmService.getMyStatistics(userDetails.getMemberId());
        return APIResponse.onSuccess(response, AlarmSuccessCode.STATISTICS_RETRIEVED);
    }
}