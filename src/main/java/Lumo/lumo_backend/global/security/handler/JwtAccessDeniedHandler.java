package Lumo.lumo_backend.global.security.handler;

import Lumo.lumo_backend.global.apiResponse.status.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 인증은 됐으나 권한이 부족할 때의 응답 (403).
 * 현재는 {@code /api/admin/**} 의 {@code hasAuthority("ADMIN")} 이 이 경로를 탄다.
 */
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponder responder;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        responder.write(response, ErrorCode.AUTH_FORBIDDEN);
    }
}
