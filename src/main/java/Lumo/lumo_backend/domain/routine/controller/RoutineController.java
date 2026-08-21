package Lumo.lumo_backend.domain.routine.controller;

import Lumo.lumo_backend.domain.member.entity.Member;
import Lumo.lumo_backend.domain.routine.dto.RoutineRespDTO;
import Lumo.lumo_backend.domain.routine.service.RoutineService;
import Lumo.lumo_backend.domain.routine.status.RoutineSuccessCode;
import Lumo.lumo_backend.global.apiResponse.APIResponse;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetails;
// import io.swagger.v3.oas.annotations.Operation;
// import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/api/routine")
@RequiredArgsConstructor
// @Tag(name="루틴 API")
public class RoutineController {

    private final RoutineService routineService;

    @PostMapping
    // @Operation(summary = "루틴 생성 API", description = "입력 받은 제목으로 루틴을 생성하는 API 입니다. QueryParameter로 title 값을 주시면 됩니다")
    public APIResponse<RoutineRespDTO.CreateRoutineDTO> createRoutine (@AuthenticationPrincipal CustomUserDetails userDetails, @RequestParam("title") String title){
        RoutineRespDTO.CreateRoutineDTO routine = routineService.createRoutine(userDetails.getMemberId(), title);
        return APIResponse.onSuccess(routine, RoutineSuccessCode.CREATE_ROUTINE_SUCCESS); // 루틴 첫 생성 시에는 서브루틴이 없으므로 null 반환
    }

    @GetMapping
    // @Operation(summary = "루틴 조회 API", description = "사용자가 생성한 루틴을 모두 조회하는 API 입니다. RequestHeader에 JWT 인증 값을 보내주시면 됩니다")
    public APIResponse<List<RoutineRespDTO.GetRoutineDTO>> getRoutine (@AuthenticationPrincipal CustomUserDetails userDetails){
        List<RoutineRespDTO.GetRoutineDTO> routineList = routineService.getRoutine(userDetails.getMemberId());
        return APIResponse.onSuccess(routineList, RoutineSuccessCode.GET_ROUTINE_SUCCESS);
    }

    @DeleteMapping
    // @Operation(summary = "루틴 삭제 API", description = "사용자가 생성한 특정 루틴을 삭제하는 API 입니다. JWT 값과 routine id 값을 QueryParameter로 보내주시면 됩니다.")
    public APIResponse<Object> deleteRoutine(@AuthenticationPrincipal CustomUserDetails userDetails, @RequestParam("routineId") Long routineId){
        routineService.deleteRoutine(userDetails.getMemberId(), routineId);
        return APIResponse.onSuccess(null, RoutineSuccessCode.DELETE_ROUTINE_SUCCESS);
    }

    @PatchMapping
    // @Operation(summary = "루틴 이름 변경 API", description = "사용자가 생성한 루틴의 이름을 변경하는 API 입니다. QueryParameter로 routineId, 새 이름 title을 보내주시면 됩니다.")
    public APIResponse<Object> renameRoutine(@AuthenticationPrincipal CustomUserDetails userDetails, @RequestParam("routineId") Long routineId, @RequestParam("title") String title) {
        routineService.renameRoutine(userDetails.getMemberId(), routineId, title);
        return APIResponse.onSuccess(null, RoutineSuccessCode.RENAME_ROUTINE_SUCCESS);
    }

}