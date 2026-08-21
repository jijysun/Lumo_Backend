package Lumo.lumo_backend.domain.setting.controller;


import Lumo.lumo_backend.domain.setting.dto.MemberDeviceCreateReqDTO;
import Lumo.lumo_backend.domain.setting.dto.MemberDeviceResDTO;
import Lumo.lumo_backend.domain.setting.dto.MemberDeviceUpdateReqDTO;
import Lumo.lumo_backend.domain.setting.service.MemberDeviceService;
import Lumo.lumo_backend.domain.setting.status.SettingSuccessCode;
import Lumo.lumo_backend.global.apiResponse.APIResponse;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetails;
// import io.swagger.v3.oas.annotations.Operation;
// import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 기기 컨트롤러
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/devices")
// @Tag(name = "기기 API", description = "사용자 기기 관련 API 입니다.")
public class DeviceController {

    private final MemberDeviceService memberDeviceService;

    @PostMapping
    // @Operation(summary = "기기 생성", description = "사용자의 기기를 DB에 등록합니다.")
    public APIResponse<Void> createDevice(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody MemberDeviceCreateReqDTO request
    ) {

        memberDeviceService.create(userDetails.getMemberId(), request);
        return APIResponse.onSuccess(null, SettingSuccessCode.DEVICE_CREATE_SUCCESS);
    }

    @GetMapping
    // @Operation(summary = "기기 목록 조회", description = "사용자의 기기 목록을 조회합니다.")
    public APIResponse<MemberDeviceResDTO> getList(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return APIResponse.onSuccess(memberDeviceService.getList(userDetails.getMemberId()), SettingSuccessCode.DEVICE_LIST_SUCCESS);
    }

    @PatchMapping("/{deviceId}")
    // @Operation(summary = "기기 수정", description = "사용자의 기기 정보를 수정합니다.")
    public APIResponse<Void> update(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long deviceId,
            @RequestBody MemberDeviceUpdateReqDTO request
    ) {

        memberDeviceService.update(userDetails.getMemberId(), request, deviceId);
        return APIResponse.onSuccess(null, SettingSuccessCode.DEVICE_UPDATE_SUCCESS);
    }

//    @DeleteMapping("/{deviceId}")
    public APIResponse<Void> delete(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long deviceId
    ) {

        memberDeviceService.delete(userDetails.getMemberId(), deviceId);
        return APIResponse.onSuccess(null, SettingSuccessCode.DEVICE_DELETE_SUCCESS);
    }


}
