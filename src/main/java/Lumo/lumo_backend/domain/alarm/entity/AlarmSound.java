package Lumo.lumo_backend.domain.alarm.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 알람 벨소리 식별자 열거형
 * - 카테고리: LOUD(시끄러운/노랑), CALM(차분한/보라), MOTIVATIONAL(동기부여/초록)
 * - mp3 파일명은 클라이언트 앱 번들 내 파일과 매핑
 */
@Getter
@RequiredArgsConstructor
public enum AlarmSound {

    // ── 시끄러운 (LOUD / 노랑) ──
    SCREAM14("비명 사운드", SoundCategory.LOUD, "scream14.mp3"),
    BIG_THUNDER("큰 천둥", SoundCategory.LOUD, "big_thunder.mp3"),
    BIG_DOG_BARKING("큰 개 짖는 소리", SoundCategory.LOUD, "big_dog_barking.mp3"),
    DESPERATE_SHOUT("절박한 외침", SoundCategory.LOUD, "desperate_shout.mp3"),
    TRAIMORY_MEGA_HORN("메가 혼", SoundCategory.LOUD, "traimory_mega_horn.mp3"),

    // ── 차분한 (CALM / 보라) ──
    CALMING_MELODY_LOOP("차분한 멜로디", SoundCategory.CALM, "calming_melody_loop.mp3"),
    THE_ISLAND_CLEARING("섬의 청명함", SoundCategory.CALM, "the_island_clearing.mp3"),
    NATIVE_FLUTE("네이티브 플룻", SoundCategory.CALM, "native_americas_style_flute_music.mp3"),
    BELL("벨 소리", SoundCategory.CALM, "bell.mp3"),
    I_WISH("I Wish", SoundCategory.CALM, "i_wish.mp3"),

    // ── 동기부여 (MOTIVATIONAL / 초록) ──
    ROCK_OF_JOY("Rock of Joy", SoundCategory.MOTIVATIONAL, "rock_of_joy.mp3"),
    EMPEROR("Emperor", SoundCategory.MOTIVATIONAL, "emperor.mp3"),
    BASIC_BEATS_AND_BASS("비트 앤 베이스", SoundCategory.MOTIVATIONAL, "basic_beats_and_bass.mp3"),
    WORK_HARD_IN_SILENCE("Work Hard in Silence", SoundCategory.MOTIVATIONAL, "work_hard_in_silence.mp3"),
    RUNAWAY("Runaway", SoundCategory.MOTIVATIONAL, "runaway.mp3");

    private final String displayName;
    private final SoundCategory category;
    private final String fileName;

    /**
     * 사운드 카테고리
     */
    @Getter
    @RequiredArgsConstructor
    public enum SoundCategory {
        LOUD("시끄러운", "#FFD600"),       // 노랑
        CALM("차분한", "#9C27B0"),          // 보라
        MOTIVATIONAL("동기부여", "#4CAF50"); // 초록

        private final String displayName;
        private final String colorHex;
    }

    /**
     * 기본 사운드인지 확인 (첫 번째 차분한 사운드를 기본값으로)
     */
    public boolean isDefaultSound() {
        return this == CALMING_MELODY_LOOP;
    }

    /**
     * 카테고리별 사운드 목록 조회
     */
    public static List<AlarmSound> findByCategory(SoundCategory category) {
        return Arrays.stream(values())
                .filter(sound -> sound.getCategory() == category)
                .collect(Collectors.toList());
    }

    /**
     * 표시 이름으로 벨소리 찾기
     */
    public static AlarmSound findByDisplayName(String displayName) {
        for (AlarmSound sound : values()) {
            if (sound.getDisplayName().equals(displayName)) {
                return sound;
            }
        }
        return CALMING_MELODY_LOOP; // 기본값 반환
    }

    /**
     * 파일명으로 벨소리 찾기
     */
    public static AlarmSound findByFileName(String fileName) {
        for (AlarmSound sound : values()) {
            if (sound.getFileName().equals(fileName)) {
                return sound;
            }
        }
        return CALMING_MELODY_LOOP;
    }
}
