package Lumo.lumo_backend.domain.alarm.entity.service;

import Lumo.lumo_backend.domain.alarm.entity.AlarmSound;
import Lumo.lumo_backend.domain.alarm.entity.AlarmSound.SoundCategory;
import Lumo.lumo_backend.domain.alarm.entity.dto.AlarmSoundResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 알람 사운드 관리 서비스
 * - 사용 가능한 사운드 목록 제공 (전체 / 카테고리별)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmSoundService {

    /**
     * 전체 사운드 목록 조회
     */
    public List<AlarmSoundResponseDto> getAvailableSounds() {
        return Arrays.stream(AlarmSound.values())
                .map(AlarmSoundResponseDto::from)
                .collect(Collectors.toList());
    }

    /**
     * 카테고리별 사운드 목록 조회
     */
    public List<AlarmSoundResponseDto> getSoundsByCategory(String category) {
        SoundCategory soundCategory = SoundCategory.valueOf(category.toUpperCase());
        return AlarmSound.findByCategory(soundCategory).stream()
                .map(AlarmSoundResponseDto::from)
                .collect(Collectors.toList());
    }

    /**
     * 사운드 식별자 유효성 검증
     */
    public boolean isValidSoundType(String soundType) {
        if (soundType == null || soundType.trim().isEmpty()) {
            return false;
        }

        try {
            AlarmSound.valueOf(soundType.toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 기본 사운드 식별자 반환
     */
    public String getDefaultSoundType() {
        return AlarmSound.CALMING_MELODY_LOOP.name();
    }
}
