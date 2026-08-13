package Lumo.lumo_backend.domain.setting.controller;

import Lumo.lumo_backend.domain.setting.dto.FeedbackCreateReqDTO;
import Lumo.lumo_backend.domain.setting.dto.FeedbackResDTO;
import Lumo.lumo_backend.domain.setting.dto.FeedbackUpdateReqDTO;
import Lumo.lumo_backend.domain.setting.service.FeedbackService;
import Lumo.lumo_backend.global.apiResponse.APIResponse;
import Lumo.lumo_backend.global.apiResponse.status.SuccessCode;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetails;
// import io.swagger.v3.oas.annotations.Operation;
// import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 피드백 컨트롤러
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/feedbacks")
// @Tag(name = "피드백 API", description = "피드백 관련 API 입니다.")
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    // @Operation(summary = "피드백 생성", description = "피드백을 생성합니다.")
    public APIResponse<Long> create(
            @AuthenticationPrincipal CustomUserDetails userDetail,
            @RequestBody FeedbackCreateReqDTO request
    ) {
        return APIResponse.onSuccess(feedbackService.create(userDetail.getMember().getId(), request), SuccessCode.OK);
    }


//    @GetMapping("/{feedbackId}")
    public APIResponse<Void> get(
            @AuthenticationPrincipal CustomUserDetails userDetail,
            @PathVariable Long feedbackId
    ) {

//        return APIResponse.onSuccess(feedbackService.get(userDetail.getMember().getId(), feedbackId), SuccessCode.OK);
        return APIResponse.onSuccess(null, SuccessCode.OK);
    }

//    @PatchMapping("/{feedbackId}")
    public APIResponse<Void> update(
            @AuthenticationPrincipal CustomUserDetails userDetail,
            @PathVariable Long feedbackId,
            @RequestBody FeedbackUpdateReqDTO request
    ) {
//        feedbackService.update(userDetail.getMember().getId(), feedbackId, request);

        return APIResponse.onSuccess(null, SuccessCode.OK);
    }


}
