package Lumo.lumo_backend.domain.encouragement.dto;

import Lumo.lumo_backend.domain.encouragement.entity.Encouragement;
// import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record EncouragementResponseDTO(
        // @Schema(description = "내용", example = "오늘의 땀방울은 내일의 보석이 된다.")
        String content
) {
    public static EncouragementResponseDTO from(Encouragement encouragement) {
        return EncouragementResponseDTO.builder()
                .content(encouragement.getContent())
                .build();
    }
}
