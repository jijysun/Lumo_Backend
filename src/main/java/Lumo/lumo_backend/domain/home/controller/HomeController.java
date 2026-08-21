package Lumo.lumo_backend.domain.home.controller;

import Lumo.lumo_backend.domain.home.dto.HomeResponseDTO;
import Lumo.lumo_backend.domain.home.service.HomeService;
import Lumo.lumo_backend.domain.home.status.HomeSuccessCode;
import Lumo.lumo_backend.global.apiResponse.APIResponse;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetails;
// import io.swagger.v3.oas.annotations.Operation;
// import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
// @Tag(name = "홈 페이지")
public class HomeController {

    private final HomeService homeService;

    // @Operation(summary = "홈 페이지의 정보를 조회합니다.", description = "오늘의 한마디, 오늘의 할 일, 최근 미션 성공")
    @GetMapping
    public APIResponse<HomeResponseDTO> home(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam LocalDate today
            ) {
        HomeResponseDTO homeResponseDTO = homeService.get(customUserDetails.getMemberId(), today);
        return APIResponse.onSuccess(homeResponseDTO, HomeSuccessCode.HOME_GET_SUCCESS);
    }
}
