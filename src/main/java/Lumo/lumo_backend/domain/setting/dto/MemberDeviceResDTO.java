package Lumo.lumo_backend.domain.setting.dto;

import Lumo.lumo_backend.domain.setting.entity.MemberDevice;
// import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
@AllArgsConstructor
public class MemberDeviceResDTO {

    // @Schema(
            // description = "기기 목록"
    // )
    private List<MemberDeviceDTO> deviceList;


    public static MemberDeviceResDTO from (List<MemberDevice> deviceList) {
        List<MemberDeviceDTO> deviceDTOList = deviceList.stream()
                .map(MemberDeviceDTO::from)
                .collect(Collectors.toList());

        return new MemberDeviceResDTO(deviceDTOList);
    }
}
