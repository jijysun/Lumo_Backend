package Lumo.lumo_backend.domain.setting.dto;


// import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class MemberDeviceUpdateReqDTO {

    // @Schema(
            // description = "기기 이름",
            // example = "lumo의 iPhone"
    // )
    @Size(min = 1, max = 25565)
    private String deviceName;


    // @Schema(
            // description = "모델 이름",
            // example = "iPhone 16 Pro Max"
    // )
    @Size(min = 1, max = 25565)
    private String modelName;


    // @Schema(
            // description = "OS 버전",
            // example = "iOS 26.1"
    // )
    @Size(min = 1, max = 25565)
    private String osVersion;

    // @Schema(
            // description = "기기 고유값",
            // example = "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"
    // )
    @Size(min = 1, max = 25565)
    private String uuid;
}
