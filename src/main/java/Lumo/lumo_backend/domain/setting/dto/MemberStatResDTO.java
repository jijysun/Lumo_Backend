package Lumo.lumo_backend.domain.setting.dto;

import Lumo.lumo_backend.domain.setting.entity.Feedback;
import Lumo.lumo_backend.domain.setting.entity.MemberStat;
// import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
@AllArgsConstructor
public class MemberStatResDTO {

    // @Schema(
            // description = "알람 활성화 횟수",
            // example = "3"
    // )
    private int alarmActivateCount = 0; // 알람 활성화 횟수


    // @Schema(
            // description = "앱 연 횟수",
            // example = "8"
    // )
    private int appOpenCount = 0; // 앱 연 횟수


    // @Schema(
            // description = "미션 완료 횟수",
            // example = "2"
    // )
    private int missionCompleteCount = 0; // 미션 완료 횟수


    public static MemberStatResDTO from(MemberStat memberStat) {
        return new MemberStatResDTO(
                memberStat.getAlarmActivateCount(),
                memberStat.getAppOpenCount(),
                memberStat.getMissionCompleteCount()
        );
    }

}
