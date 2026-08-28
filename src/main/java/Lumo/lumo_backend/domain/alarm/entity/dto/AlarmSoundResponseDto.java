package Lumo.lumo_backend.domain.alarm.entity.dto;

import Lumo.lumo_backend.domain.alarm.entity.AlarmSound;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class AlarmSoundResponseDto {

    private String soundType;      // enum name (ex: "BIG_THUNDER")
    private String displayName;    // 표시 이름 (ex: "큰 천둥")
    private String fileName;       // mp3 파일명 (ex: "big_thunder.mp3")
    private String category;       // 카테고리 enum name (ex: "LOUD")
    private String categoryName;   // 카테고리 표시 이름 (ex: "시끄러운")
    private String categoryColor;  // 카테고리 색상 HEX (ex: "#FFD600")
    private Boolean isDefault;

    public static AlarmSoundResponseDto from(AlarmSound sound) {
        return AlarmSoundResponseDto.builder()
                .soundType(sound.name())
                .displayName(sound.getDisplayName())
                .fileName(sound.getFileName())
                .category(sound.getCategory().name())
                .categoryName(sound.getCategory().getDisplayName())
                .categoryColor(sound.getCategory().getColorHex())
                .isDefault(sound.isDefaultSound())
                .build();
    }
}
