package Lumo.lumo_backend.domain.setting.dto;

import Lumo.lumo_backend.domain.setting.entity.MemberDevice;
// import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class MemberDeviceDTO {

    // @Schema(
            // description = "기기 ID",
            // example= "1"
    // )
    @Positive(message = "올바른 아이디가 아닙니다.")
    private Long id;

    // @Schema(
            // description = "기기명",
            // example = "iPhone 16 Pro Max"
    // )
    private String deviceName;
    
    // @Schema(
            // description = "모델명",
            // example = "IP16PM"
    // )
    private String modelName;

    // @Schema(
            // description = "운영체제 버전",
            // example = "iOS 26.1"
    // )
    private String osVersion;

    // @Schema(
            // description = "기기 고유값",
            // example = "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"
    // )
    private String uuid;


    public static MemberDeviceDTO from(MemberDevice memberDevice) {
        return new MemberDeviceDTO(
                memberDevice.getId(),
                memberDevice.getDeviceName(),
                memberDevice.getModelName(),
                memberDevice.getOsVersion(),
                memberDevice.getUuid()
        );
    }
}
