package Lumo.lumo_backend.domain.setting.dto;

import Lumo.lumo_backend.domain.setting.entity.Notice;
// import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class NoticeResponseDTO {
    private Long id;
    // @Schema(description = "종류", example = "기능")
    private String type;
    // @Schema(description = "제목", example = "새로운 벨소리가 추가되었어요")
    private String title;
    // @Schema(description = "내용", example = "이제 새로운 벨소리를 사용할 수 있어요")
    private String content;
    // @Schema(description = "생성일")
    private LocalDateTime createdAt;
    // @Schema(description = "수정일")
    private LocalDateTime updatedAt;

    public static NoticeResponseDTO from(Notice notice) {
        return new NoticeResponseDTO(
                notice.getId(),
                notice.getType(),
                notice.getTitle(),
                notice.getContent(),
                notice.getCreatedAt(),
                notice.getUpdatedAt()
        );
    }
}
