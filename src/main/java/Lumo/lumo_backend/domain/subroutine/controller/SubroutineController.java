package Lumo.lumo_backend.domain.subroutine.controller;

import Lumo.lumo_backend.domain.member.entity.Member;
import Lumo.lumo_backend.domain.routine.status.RoutineSuccessCode;
import Lumo.lumo_backend.domain.subroutine.service.SubroutineService;
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
@RequestMapping("/api/subroutine")
@RequiredArgsConstructor
// @Tag(name = "서브루틴 API", description = "루틴 내 하위 서브루틴 관련 API 입니다.")
public class SubroutineController {

    private final SubroutineService subroutineService;

    /*
     *
     * 관리하고자 하는 서브루틴을 보유하고 있는 사용자 인지에 대한 검증이 필요!
     *
     * */

    @PostMapping
    // @Operation(summary = "서브루틴 생성 API", description = "입력 받은 제목으로 서브루틴을 생성하는 API 입니다. QueryParameter로 루틴 Id와 title 값을 주시면 됩니다")
    public APIResponse<Long> createSubroutine(@AuthenticationPrincipal CustomUserDetails userDetails, @RequestParam ("routineId") Long routineId, @RequestParam("title") String title) {
        return APIResponse.onSuccess(subroutineService.createSubroutine(userDetails.getMemberId(), routineId, title), RoutineSuccessCode.CREATE_ROUTINE_SUCCESS); /// id 반환 필요!
    }

    @DeleteMapping
    // @Operation(summary = "서브루틴 삭제 API", description = "QueryParameter로 주신 subroutineId를 통해 서브루틴을 삭제하는 API 입니다.")
    public APIResponse<Object> deleteSubroutine(@AuthenticationPrincipal CustomUserDetails userDetails, @RequestParam ("subroutineId") Long subroutineId) {
        subroutineService.deleteSubroutine(userDetails.getMemberId(), subroutineId);
        return APIResponse.onSuccess(null, RoutineSuccessCode.DELETE_ROUTINE_SUCCESS);
    }

    @PatchMapping
    // @Operation(summary = "서브루틴 이름 변경 API", description = "QueryParameter로 주신 subroutineId와 title을 통해 서브루틴의 이름을 변경하는 API 입니다.")
    public APIResponse<Object> renameSubroutine(@AuthenticationPrincipal CustomUserDetails userDetails, @RequestParam ("subroutineId") Long subroutineId, @RequestParam("title") String title) {
        subroutineService.renameSubroutine(userDetails.getMemberId(), subroutineId, title);
        return APIResponse.onSuccess(null, RoutineSuccessCode.RENAME_ROUTINE_SUCCESS);
    }

    @PostMapping("/check")
    // @Operation(summary = "서브루틴 체크 API", description = "QueryParameter로 주신 subroutineId를 통해 서브루틴 수행을 체크하는 API 입니다.")
    public APIResponse<Object> checkSubroutine (@AuthenticationPrincipal CustomUserDetails userDetails, @RequestParam ("id") Long subroutineId) {
        subroutineService.checkSubroutine(userDetails.getMemberId(), subroutineId);
        return APIResponse.onSuccess(null, RoutineSuccessCode.CHECK_ROUTINE_SUCCESS);
    }

}
