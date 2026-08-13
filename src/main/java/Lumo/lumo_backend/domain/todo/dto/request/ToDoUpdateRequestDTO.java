package Lumo.lumo_backend.domain.todo.dto.request;

// import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record ToDoUpdateRequestDTO(
        // @Schema(description = "내용", example = "쓰레기 버리기")
        @NotBlank
        String content
) {
}
