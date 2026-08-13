package Lumo.lumo_backend.domain.setting.controller;

import Lumo.lumo_backend.domain.setting.dto.MemberSettingResDTO;
import Lumo.lumo_backend.domain.setting.dto.MemberSettingUpdateReqDTO;
import Lumo.lumo_backend.domain.setting.service.MemberSettingService;
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
 * 설정 컨트롤러
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/setting")
// @Tag(name = "설정 API", description = "설정 관련 API입니다.")
public class SettingController {

    private final MemberSettingService memberSettingService;

    @GetMapping
    // @Operation(summary = "설정 조회", description = "사용자의 설정을 조회합니다.")
    public APIResponse<MemberSettingResDTO> get(
            @AuthenticationPrincipal CustomUserDetails userDetail
    ) {

        return APIResponse.onSuccess(memberSettingService.get(userDetail.getMember().getId()), SettingSuccessCode.SETTING_DETAIL_SUCCESS);
    }


    @PatchMapping
    // @Operation(summary = "설정 수정", description = "사용자의 설정을 수정합니다.")
    public APIResponse<Void> update(
            @AuthenticationPrincipal CustomUserDetails userDetail,
            @RequestBody MemberSettingUpdateReqDTO request
    ) {
        memberSettingService.update(userDetail.getMember().getId(), request);
        return APIResponse.onSuccess(null, SettingSuccessCode.SETTING_UPDATE_SUCCESS);
    }


}
