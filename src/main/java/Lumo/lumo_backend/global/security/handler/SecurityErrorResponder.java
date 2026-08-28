package Lumo.lumo_backend.global.security.handler;

import Lumo.lumo_backend.global.apiResponse.APIResponse;
import Lumo.lumo_backend.global.apiResponse.basecode.BaseErrorCode;
import Lumo.lumo_backend.global.apiResponse.dto.ErrorReasonDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 시큐리티 계층(필터 · EntryPoint · AccessDeniedHandler)에서 {@link APIResponse} 형식으로 직접 응답을 쓴다.
 *
 * <p>필터는 {@code DispatcherServlet} 앞에서 동작하므로 {@code @RestControllerAdvice}(ExceptionAdvice)가
 * 잡지 못한다. 그래서 인증 실패 구간만 응답 규약이 깨지고 컨테이너 기본 500/403 HTML 이 나가고 있었다 (H-1).
 * 여기서 {@code ExceptionAdvice.handleExceptionInternal} 과 동일하게
 * {@code code = ErrorReasonDTO.getCode()}, {@code message = getMessage()} 로 맞춰 형식을 통일한다.
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorResponder {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, BaseErrorCode errorCode) throws IOException {
        // 이미 커밋된 응답에 다시 쓰면 IllegalStateException 이 난다(필터 체인 중복 진입 방어).
        if (response.isCommitted()) {
            return;
        }

        ErrorReasonDTO reason = errorCode.getReasonHttpStatus();

        response.setStatus(reason.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(
                response.getWriter(),
                APIResponse.onFailure(reason.getCode(), reason.getMessage(), null));
    }
}
