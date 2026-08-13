package Lumo.lumo_backend.domain.setting.controller.notice;

import Lumo.lumo_backend.domain.member.entity.Member;
import Lumo.lumo_backend.domain.member.entity.memberEnum.MemberRole;
import Lumo.lumo_backend.domain.setting.dto.NoticeCreateRequestDTO;
import Lumo.lumo_backend.domain.setting.dto.NoticeResponseDTO;
import Lumo.lumo_backend.domain.setting.service.NoticeService;
import Lumo.lumo_backend.domain.setting.status.SettingSuccessCode;
import Lumo.lumo_backend.global.apiResponse.APIResponse;
import Lumo.lumo_backend.global.apiResponse.status.ErrorCode;
import Lumo.lumo_backend.global.exception.GeneralException;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetails;
// import io.swagger.v3.oas.annotations.Operation;
// import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/admin/notices")
// @Tag(name = "공지사항 관리", description = "관리자 계정 로그인 필요")
public class AdminNoticeController {

    private final NoticeService noticeService;

    // @Operation(summary = "공지사항 생성")
    @PostMapping
    public APIResponse<NoticeResponseDTO> createNotice(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestBody @Valid NoticeCreateRequestDTO noticeCreateRequestDTO
    ) {
        verifyAdmin(customUserDetails.getMember());
        NoticeResponseDTO noticeResponseDTO = noticeService.create(noticeCreateRequestDTO);
        return APIResponse.onSuccess(noticeResponseDTO, SettingSuccessCode.NOTICE_CREATE_SUCCESS);
    }

    // @Operation(summary = "공지사항 수정")
    @PatchMapping("/{noticeId}")
    public APIResponse<NoticeResponseDTO> updateNotice(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Long noticeId,
            @RequestBody @Valid NoticeCreateRequestDTO noticeCreateRequestDTO
    ) {
        verifyAdmin(customUserDetails.getMember());
        NoticeResponseDTO noticeResponseDTO = noticeService.update(noticeId, noticeCreateRequestDTO);
        return APIResponse.onSuccess(noticeResponseDTO, SettingSuccessCode.NOTICE_UPDATE_SUCCESS);
    }


    // @Operation(summary = "공지사항 삭제")
    @DeleteMapping("/{noticeId}")
    public APIResponse<Void> deleteNotice(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Long noticeId
    ) {
        verifyAdmin(customUserDetails.getMember());
        noticeService.softDelete(noticeId);
        return APIResponse.onSuccess(null, SettingSuccessCode.NOTICE_DELETE_SUCCESS);
    }

    private void verifyAdmin(Member member) {
        if(member.getRole()!= MemberRole.ADMIN){
            throw new GeneralException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

}