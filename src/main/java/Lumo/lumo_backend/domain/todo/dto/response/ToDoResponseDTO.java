package Lumo.lumo_backend.domain.todo.dto.response;

import Lumo.lumo_backend.domain.todo.entity.ToDo;
// import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record ToDoResponseDTO(
        // @Schema(description = "할 일 아이디", example = "1")
        Long id,

        // @Schema(description = "내용", example = "쓰레기 버리기")
        String content,

        // @Schema(description = "날짜", example = "2025-01-01")
        LocalDate eventDate
) {
        public static ToDoResponseDTO from(ToDo toDo){
                return ToDoResponseDTO.builder()
                        .id(toDo.getId())
                        .content(toDo.getContent())
                        .eventDate(toDo.getEventDate())
                        .build();
        }
}
