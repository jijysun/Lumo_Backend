package Lumo.lumo_backend.domain.setting.dto;

import Lumo.lumo_backend.domain.setting.entity.Feedback;
import Lumo.lumo_backend.domain.setting.entity.Feedback;
// import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;
import java.util.stream.Collectors;

@Getter
@AllArgsConstructor
public class FeedbackResDTO {

    // @Schema(
            // description = "피드백 리스트"
    // )
    private List<FeedbackDTO> feedbackList;


    public static FeedbackResDTO from (List<Feedback> feedbackList) {
        List<FeedbackDTO> feedbackDTOList = feedbackList.stream()
                .map(FeedbackDTO::from)
                .collect(Collectors.toList());

        return new FeedbackResDTO(feedbackDTOList);
    }
}
