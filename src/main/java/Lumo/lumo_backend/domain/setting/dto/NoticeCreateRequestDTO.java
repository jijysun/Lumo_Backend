package Lumo.lumo_backend.domain.setting.dto;

// import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class NoticeCreateRequestDTO {
    // @Schema(description = "종류", example = "기능")
    @Size(max = 255)
    private String type;

    // @Schema(description = "제목", example = "새로운 벨소리가 추가되었어요")
    @Size(min = 1, max = 100)
    private String title;

    // @Schema(description = "내용", example = "이제 새로운 벨소리를 사용할 수 있어요")
    @Size(min = 1, max = 255)
    private String content;
}
