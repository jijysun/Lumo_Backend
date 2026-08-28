package Lumo.lumo_backend.domain.todo.dto.request;

// import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ToDoCreateRequestDTO(
        // @Schema(description = "날짜", example = "2025-01-01")
        @NotNull
        LocalDate eventDate,

        // @Schema(description = "내용", example = "쓰레기 버리기")
        @NotBlank
        String content
) {
}