package Lumo.lumo_backend.domain.todo.controller;

import Lumo.lumo_backend.domain.todo.dto.request.ToDoCreateRequestDTO;
import Lumo.lumo_backend.domain.todo.dto.request.ToDoUpdateRequestDTO;
import Lumo.lumo_backend.domain.todo.dto.response.ToDoResponseDTO;
import Lumo.lumo_backend.domain.todo.service.ToDoService;
import Lumo.lumo_backend.domain.todo.status.ToDoSuccessCode;
import Lumo.lumo_backend.global.apiResponse.APIResponse;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetails;
// import io.swagger.v3.oas.annotations.Operation;
// import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/to-do")
@RequiredArgsConstructor
@Slf4j
// @Tag(name = "할 일")
public class ToDoController {

    private final ToDoService toDoService;

    // @Operation(summary = "할 일 생성")
    @PostMapping
    public APIResponse<ToDoResponseDTO> create(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid ToDoCreateRequestDTO toDoCreateRequestDTO
    ) {
        ToDoResponseDTO toDoResponseDTO = toDoService.create(userDetails.getMemberId(), toDoCreateRequestDTO);
        return APIResponse.onSuccess(toDoResponseDTO, ToDoSuccessCode.CREATE_TODO_SUCCESS);
    }

    // @Operation(summary = "할 일 수정")
    @PatchMapping("/{toDoId}")
    public APIResponse<ToDoResponseDTO> update(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long toDoId,
            @RequestBody @Valid ToDoUpdateRequestDTO toDoUpdateRequestDTO
    ) {
        ToDoResponseDTO toDoResponseDTO = toDoService.update(userDetails.getMemberId(), toDoId, toDoUpdateRequestDTO);
        return APIResponse.onSuccess(toDoResponseDTO, ToDoSuccessCode.UPDATE_TODO_SUCCESS);
    }

    // @Operation(summary = "할 일 삭제")
    @DeleteMapping("/{toDoId}")
    public APIResponse<Void> delete(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long toDoId
    ) {
        toDoService.delete(userDetails.getMemberId(), toDoId);
        return APIResponse.onSuccess(null, ToDoSuccessCode.DELETE_TODO_SUCCESS);
    }

    // @Operation(summary = "일별 할 일 목록 조회")
    @GetMapping
    public APIResponse<List<ToDoResponseDTO>> findToDoListByEventDate(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam LocalDate eventDate
    ) {
        List<ToDoResponseDTO> toDoList = toDoService.findToDoListByEventDate(userDetails.getMemberId(), eventDate);
        return APIResponse.onSuccess(toDoList, ToDoSuccessCode.GET_TODO_SUCCESS);
    }

    // @Operation(summary = "오늘의 할 일 브리핑")
    @GetMapping("/briefing")
    public APIResponse<String> getBriefing(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam LocalDate today
    ) {
        String briefing = toDoService.getBriefing(userDetails.getMemberId(), today);
        return APIResponse.onSuccess(briefing, ToDoSuccessCode.GET_TODO_SUCCESS);
    }
}
