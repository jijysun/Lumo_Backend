package Lumo.lumo_backend.global.security.config;

import Lumo.lumo_backend.global.security.filter.JWTAuthenticationFilter;
import Lumo.lumo_backend.global.security.handler.JwtAccessDeniedHandler;
import Lumo.lumo_backend.global.security.handler.JwtAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JWTAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    /**
     * 허용 Origin 목록 (M-12). 콤마 구분. 기본값은 로컬 개발용.
     * 운영에서는 .env 의 CORS_ALLOWED_ORIGINS 로 실제 도메인을 주입한다.
     */
    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String[] allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        /*
         * (20260816 C-1 수정) 기본값을 permitAll → authenticated 로 뒤집는다.
         *
         * 이전 설정은 `/api/members/me/**`(복수형 + /me)를 보호했으나 MemberController 의 실제 매핑은
         * `/api/member`(단수형)라 한 글자도 겹치지 않았고, 나머지가 전부 anyRequest().permitAll() 로
         * 떨어져 member API 전체가 무인증으로 열려 있었다. 특히 change-pw 는 이메일만 알면
         * 임의 계정의 비밀번호를 바꿀 수 있었다.
         *
         * 매처는 선언 순서대로 평가되며 먼저 매칭된 규칙이 이긴다.
         * https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html
         */

        http
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class) // 해당 필터 전에 실행되어야 함
                .sessionManagement(session -> {
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS);
                })
                .csrf(csrf -> csrf.disable())
                // (M-12) 그동안 actuator 에만 CORS 가 있고 애플리케이션 API 에는 없었다.
                // iOS 클라이언트라 당장 문제가 없었을 뿐, 웹 연동 시 즉시 막힌다.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 인증 실패(401) / 인가 실패(403) 응답을 APIResponse 형식으로 통일한다 (H-1).
                // 등록하지 않으면 formLogin·httpBasic 이 없는 이 프로젝트에서는 본문 없는 403 이 나간다.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests((auth) -> {
                    auth
                            // ── 인프라 ────────────────────────────────────────────────
                            // scripts/deploy.sh 의 블루-그린 헬스체크와 Prometheus 스크레이프
                            // (monitoring/prometheus/prometheus.yaml → /actuator/prometheus)가 타는 경로다.
                            // 여기를 막으면 배포 판정과 관측이 통째로 죽는다.
                            .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()

                            // ── 인증 이전 단계 (가입 · 로그인 · 비밀번호 재설정) ──────────
                            // GET /api/member/login 은 "로그인 방식 조회"라 인증이 필요하다.
                            // POST 와 경로가 같으므로 HTTP 메서드까지 지정해 분리한다.
                            .requestMatchers(HttpMethod.POST, "/api/member/login").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/member/signin").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/member/request-code").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/member/verify-code").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/member/find-email").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/member/email-duplicate").permitAll()

                            // 비밀번호 재설정은 "로그인할 수 없는 사용자"가 쓰는 경로라 인증을 요구할 수 없다.
                            // 대신 서비스 계층에서 이메일 인증 티켓(verified:{email})을 소비하도록 막는다 (C-2).
                            .requestMatchers(HttpMethod.PATCH, "/api/member/change-pw").permitAll()

                            // ── 관리자 ────────────────────────────────────────────────
                            // hasRole() 이 아니라 hasAuthority() 다. CustomUserDetails 가
                            // ROLE_ 접두사 없이 "ADMIN"/"USER" 를 그대로 반환하기 때문
                            // (MemberRole.getAuthority() 는 접두사를 붙이지만 호출되는 곳이 없다).
                            .requestMatchers("/api/admin/**").hasAuthority("ADMIN")

                            // ── 기본값 ────────────────────────────────────────────────
                            .anyRequest().authenticated();
                });
        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // allowCredentials(true) 와 "*" 는 동시에 쓸 수 없다(스펙상 거부).
        // RefreshToken 을 쿠키로 주고받으므로 credentials 는 필요하고, Origin 은 명시해야 한다.
        config.setAllowedOrigins(Arrays.asList(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // 클라이언트가 재발급된 AT 를 읽어야 한다 (JWTAuthenticationFilter 가 헤더로 내려준다).
        config.setExposedHeaders(List.of(HttpHeaders.AUTHORIZATION));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
