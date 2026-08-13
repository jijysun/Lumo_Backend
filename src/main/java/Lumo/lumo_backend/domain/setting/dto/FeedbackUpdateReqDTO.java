package Lumo.lumo_backend.domain.setting.dto;

// import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class FeedbackUpdateReqDTO {

    // @Schema(
            // description = "피드백 제목",
            // example= "피드백 제목 테스트"
    // )
    @Size(min = 1, max = 100)
    private String title;


    // @Schema(
            // description = "피드백 내용",
            // example= "피드백 내용 테스트"
    // )
    @Size(min = 1, max = 25565)
    private String content;

    // @Schema(
            // description = "피드백 답변 받을 이메일",
            // example= "lumo@example.com"
    // )
    @Email(message = "이메일 형식이 아닙니다.")
    private String email;
}