package Lumo.lumo_backend.domain.setting.dto;

import Lumo.lumo_backend.domain.setting.entity.memberSetting.*;
// import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Builder
@Getter
@AllArgsConstructor
public class MemberSettingResDTO {

    // @Schema(
            // description = "테마",
            // example = "SYSTEM"
    // )
    private Theme theme;


    // @Schema(
            // description = "언어",
            // example = "KO"
    // )
    private Language language;


    // @Schema(
            // description = "배터리 절약 모드",
            // example = "false"
    // )
    private boolean batterySaving;

    // @Schema(
            // description = "미션 기본 종류",
            // example = "MATH"
    // )
    private AlarmOffMissionDefaultType alarmOffMissionDefaultType;

    // @Schema(
            // description = "미션 기본 난이도",
            // example = "MEDIUM"
    // )
    private AlarmOffMissionDefaultLevel alarmOffMissionDefaultLevel;

    // @Schema(
            // description = "미션 기본 지속시간",
            // example = "10"
    // )
    private Integer alarmOffMissionDefaultDuration;

    // @Schema(
            // description = "브리핑 문장",
            // example = "브리핑 문장 테스트"
    // )
    private String briefingSentence;

    // @Schema(
            // description = "브리핑 목소리",
            // example = "WOMAN"
    // )
    private BriefingVoiceDefaultType briefingVoiceDefaultType;


    // @Schema(
            // description = "스마트 브리핑",
            // example = "false"
    // )
    private boolean smartBriefing;

    public static MemberSettingResDTO from(MemberSetting memberSetting) {
        return new MemberSettingResDTO(
                memberSetting.getTheme(),
                memberSetting.getLanguage(),
                memberSetting.isBatterySaving(),
                memberSetting.getAlarmOffMissionDefaultType(),
                memberSetting.getAlarmOffMissionDefaultLevel(),
                memberSetting.getAlarmOffMissionDefaultDuration(),
                memberSetting.getBriefingSentence(),
                memberSetting.getBriefingVoiceDefaultType(),
                memberSetting.isSmartBriefing()
        );
    }
    public static MemberSettingResDTO fromMap(Map<Object, Object> map) {

        return MemberSettingResDTO.builder()
                .theme(Theme.valueOf((String) map.get("theme")))
                .language(Language.valueOf((String) map.get("language")))
                .batterySaving(Boolean.parseBoolean((String) map.get("batterySaving")))
                .alarmOffMissionDefaultType(
                        AlarmOffMissionDefaultType.valueOf((String) map.get("alarmOffMissionDefaultType")))
                .alarmOffMissionDefaultLevel(
                        AlarmOffMissionDefaultLevel.valueOf((String) map.get("alarmOffMissionDefaultLevel")))
                .alarmOffMissionDefaultDuration(
                        Integer.parseInt((String) map.get("alarmOffMissionDefaultDuration")))
                .briefingSentence((String) map.get("briefingSentence"))
                .briefingVoiceDefaultType(
                        BriefingVoiceDefaultType.valueOf((String) map.get("briefingVoiceDefaultType")))
                .smartBriefing(Boolean.parseBoolean((String) map.get("smartBriefing")))
                .build();
    }


}
