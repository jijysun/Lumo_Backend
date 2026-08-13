package Lumo.lumo_backend.domain.setting.dto;

import Lumo.lumo_backend.domain.setting.entity.Feedback;
// import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class FeedbackDTO {


    // @Schema(
            // description = "피드백 ID",
            // example= "1"
    // )
    private Long id;

    // @Schema(
            // description = "피드백 제목",
            // example= "피드백 제목 테스트"
    // )
    private String title;


    // @Schema(
            // description = "피드백 내용",
            // example= "피드백 내용 테스트"
    // )
    private String content;

    // @Schema(
            // description = "피드백 답변 받을 이메일",
            // example= "lumo@example.com"
    // )
    private String email;

    public static FeedbackDTO from(Feedback feedback) {
        return new FeedbackDTO(
                feedback.getId(),
                feedback.getTitle(),
                feedback.getContent(),
                feedback.getEmail()
        );
    }
}
