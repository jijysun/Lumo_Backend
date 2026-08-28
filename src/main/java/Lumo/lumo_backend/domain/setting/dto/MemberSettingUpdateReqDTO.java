package Lumo.lumo_backend.domain.setting.dto;

import Lumo.lumo_backend.domain.setting.entity.memberSetting.*;
// import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MemberSettingUpdateReqDTO {

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
    @Size(min = 1, max = 25565)
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

}
