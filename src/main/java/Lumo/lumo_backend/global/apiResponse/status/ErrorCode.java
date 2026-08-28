package Lumo.lumo_backend.global.apiResponse.status;


import Lumo.lumo_backend.global.apiResponse.basecode.BaseErrorCode;
import Lumo.lumo_backend.global.apiResponse.dto.ErrorReasonDTO;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode implements BaseErrorCode {

    // 예외 작성 예시 입니다.
    TEST_EXCEPTION (HttpStatus.BAD_REQUEST, "TEST4000", "테스트 예외 입니다."),

    // 여기서부터 이어서 작성해주시기 바랍니다.

    INVALID_JSON(HttpStatus.BAD_REQUEST,
            "COMMON400_2",
            "JSON 형식이 올바르지 않습니다."),

    REQUEST_INVALID(HttpStatus.BAD_REQUEST, "SERVER_4000", "잘못된 요청입니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "SERVER_4000", "잘못된 요청입니다."),
    REQUEST_MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "SERVER_4001", "필수 파라미터가 누락되었습니다."),
    REQUEST_INVALID_FORMAT(HttpStatus.BAD_REQUEST, "SERVER_4002", "JSON 형식 또는 데이터 타입 오류입니다."),

    AUTH_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "SERVER_4100", "인증 정보가 없습니다."),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "SERVER_4101", "토큰이 만료되었습니다."),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "SERVER_4102", "유효하지 않은 토큰입니다."),
    /*
     * AT 수명을 1시간 → 15분으로 줄이고 RTR 재사용 감지로 대체했기 때문에
     * "블랙리스트에 있는 토큰" 이라는 상태 자체가 더는 존재X
     */
//    BLACKLISTED_TOKEN(HttpStatus.UNAUTHORIZED, "SERVER_4103", "블랙리스트에 있는 토큰입니다."),
    CANNOT_FOUND_RT(HttpStatus.UNAUTHORIZED, "SERVER_4104", "리프레쉬 토큰을 찾을 수 없습니다."),

    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "SERVER_4300", "접근 권한이 없습니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "SERVER_4290", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),

    // 500 계열: 서버 측 오류
    SYSTEM_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_5000", "서버 내부 오류가 발생했습니다."),
    SYSTEM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SERVER_5300", "현재 서비스 이용이 불가능합니다."),


    /// SERVER
    INTERNAL_SERVER_ERROR (HttpStatus.INTERNAL_SERVER_ERROR, "SERVER5000", "서버 에러 입니다. 관리자에게 문의 부탁 드립니다")






    ;




    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    @Override
    public ErrorReasonDTO getReason() {
        return ErrorReasonDTO.builder()
                .isSuccess(false)
                .code(this.name())
                .message(message)
                .build();
    }

    @Override
    public ErrorReasonDTO getReasonHttpStatus() {
        return ErrorReasonDTO.builder()
                .isSuccess(false)
                .httpStatus(httpStatus)
                .code(this.name())
                .message(message)
                .build();
    }

    @Override
    public String getCodeName() {
        return this.name();  // enum 객체의 이름 반환
    }
}