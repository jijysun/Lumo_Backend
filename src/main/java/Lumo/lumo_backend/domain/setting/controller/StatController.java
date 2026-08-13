package Lumo.lumo_backend.domain.setting.controller;


import Lumo.lumo_backend.domain.setting.dto.MemberStatResDTO;
import Lumo.lumo_backend.domain.setting.service.MemberStatService;
import Lumo.lumo_backend.domain.setting.status.SettingSuccessCode;
import Lumo.lumo_backend.global.apiResponse.APIResponse;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetails;
// import io.swagger.v3.oas.annotations.Operation;
// import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


@RestController
@Slf4j
@RequestMapping("/api/stats")
@RequiredArgsConstructor
// @Tag(name = "통계 API")
public class StatController {

    private final MemberStatService memberStatService;

    @GetMapping
    // @Operation(summary = "통계 조회 API", description = "사용자가 서비스 내에서 남긴 기록에 대한 통계를 조회하는 API 입니다.")
    public APIResponse<MemberStatResDTO> get(
            @AuthenticationPrincipal CustomUserDetails userDetail
    ) {

        return APIResponse.onSuccess(memberStatService.get(userDetail.getMember().getId()), SettingSuccessCode.STAT_DETAIL_SUCCESS);
    }

}
