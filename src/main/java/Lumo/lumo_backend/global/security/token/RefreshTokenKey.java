package Lumo.lumo_backend.global.security.token;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * RefreshToken 관련 Redis 키를 조립한다 (H-9).
 *
 * <p><b>왜 기기 단위인가</b> — 이전 키는 {@code refresh:{email}} 로 <b>계정당 1개</b>였다.
 * 그래서 기기 B 가 로그인하면 기기 A 의 RT 가 덮어써졌고, 이후 기기 A 가 갱신을 시도하면
 * G-9 의 재사용 감지가 <b>정상 사용을 탈취로 오인</b>해 두 기기의 세션을 모두 파괴했다.
 * 키를 기기 단위로 쪼개면 "저장값과 다르다 = 이미 쓴 RT" 라는 G-9 의 전제가 비로소 성립한다.
 *
 * <p>키 조립이 3개 파일(필터·로그인·로그아웃)에 흩어져 있으면 한 곳만 고쳐도 조용히 어긋난다.
 * 그래서 조립을 이 클래스로 모은다.
 */
public final class RefreshTokenKey {

    public static final String DEVICE_ID_HEADER = "X-Device-Id";

    /** 헤더가 없거나 형식이 어긋날 때 쓰는 값. 폴백끼리는 여전히 1개라 기존 동작과 같다(하위호환). */
    public static final String DEFAULT_DEVICE_ID = "default";

    private static final String REFRESH_PREFIX = "refresh:";
    private static final String PREV_RT_PREFIX = "prev_rt:";

    /**
     * deviceId 는 Redis 키의 일부가 되므로 형식을 강제
     * 콜론이 섞이면 키 구분자가 깨지고, 길이 제한이 없으면 임의 길이 키를 만들 수 있다.
     */
    private static final Pattern SAFE_DEVICE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private RefreshTokenKey() {
    }

    /**
     * 요청에서 deviceId 를 꺼낸다. 없거나 형식이 어긋나면 {@link #DEFAULT_DEVICE_ID} 로 떨어진다.
     *
     * <p>클라이언트가 헤더를 보내기 시작하면 자동으로 기기가 분리되고, 아직 안 보내도
     * 폴백값 하나로 묶여 기존과 동일하게 동작한다 — 클라이언트 수정을 기다리지 않아도 된다.
     */
    public static String resolveDeviceId(HttpServletRequest request) {
        if (request == null) {
            return DEFAULT_DEVICE_ID;
        }
        return sanitize(request.getHeader(DEVICE_ID_HEADER));
    }

    public static String sanitize(String rawDeviceId) {
        if (rawDeviceId == null) {
            return DEFAULT_DEVICE_ID;
        }
        String trimmed = rawDeviceId.trim();

        // 형식이 어긋나면 조용히 잘라 쓰지 않고 폴백으로 보냄 = 잘라 쓰면 서로 다른 기기가 같은 키로 충돌 가능
        return SAFE_DEVICE_ID.matcher(trimmed).matches() ? trimmed : DEFAULT_DEVICE_ID;
    }

    /** 현재 유효한 RT. */
    public static String refresh(String email, String deviceId) {
        return REFRESH_PREFIX + email + ":" + deviceId;
    }

    /** 회전 직후의 직전 RT (재시도 유예 창). */
    public static String prevRt(String email, String deviceId) {
        return PREV_RT_PREFIX + email + ":" + deviceId;
    }
}
