package Lumo.lumo_backend.global.security.handler;

import Lumo.lumo_backend.global.apiResponse.status.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 인증되지 않은 요청이 보호 자원에 접근했을 때의 응답 (401).
 *
 * <p>C-1 로 기본값을 {@code .anyRequest().authenticated()} 로 뒤집으면서 필요해졌다.
 * 등록하지 않으면 스프링 시큐리티가 기본 EntryPoint 를 쓰는데, 이 프로젝트는 formLogin·httpBasic 을
 * 쓰지 않아 <b>본문 없는 403</b> 이 나간다 — 401 이어야 할 자리이고 APIResponse 형식도 아니다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponder responder;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        responder.write(response, ErrorCode.AUTH_UNAUTHORIZED);
    }
}
