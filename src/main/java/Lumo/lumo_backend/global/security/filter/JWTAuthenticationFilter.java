package Lumo.lumo_backend.global.security.filter;

import Lumo.lumo_backend.global.apiResponse.status.ErrorCode;
import Lumo.lumo_backend.global.exception.GeneralException;
import Lumo.lumo_backend.global.security.jwt.JWT;
import Lumo.lumo_backend.global.security.handler.SecurityErrorResponder;
import Lumo.lumo_backend.global.security.jwt.JWTProvider;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetailsService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
@Component
public class JWTAuthenticationFilter extends OncePerRequestFilter {

    private final JWTProvider jwtProvider;
    private final RedisTemplate<String, String> redisTemplate;
    private final CustomUserDetailsService customUserDetailsService;
    private final SecurityErrorResponder responder;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String accessToken = resolveAccessToken(request);

        /// jwtProvider 에서 인증 조회 + 토큰 검증이 필요!
        try{
            if(accessToken != null && jwtProvider.validateToken(accessToken)){ // 비었거나, 올바르지 않거나

                String isBlackListed = redisTemplate.opsForValue().get("blacklist:" + accessToken);
                if (isBlackListed != null){
                    log.warn("[JWTAuthenticationFilter] - Using BlackListed Token!");
                    throw new GeneralException(ErrorCode.BLACKLISTED_TOKEN);
                }

                Authentication authentication = jwtProvider.getAuthentication(accessToken);
                if (authentication != null) {
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
                /*else {
                    log.info("[JWTAuthenticationFilter] - Invalid Token, Dont save authentication!");
                }*/
            }
            else {
//                log.warn("[JWTAuthenticationFilter] - This is request with Empty or Invalid Token");
//            throw new GeneralException(ErrorCode.AUTH_UNAUTHORIZED);
            }
        }
        catch (ExpiredJwtException e){
            log.warn("[JWTAuthenticationFilter] - AT expired, attempting to refresh token");
            try {
                handleExpiredAccessToken(request, response, e);
            }
            // RT 부재/불일치. 아래 catch(JwtException|IllegalArgumentException) 는 상속 관계가 아니라
            // GeneralException 을 잡지 못했고, 그대로 서블릿 컨테이너까지 전파돼 500 HTML 이 나갔다 (H-1).
            catch (GeneralException ge) {
                responder.write(response, ge.getErrorCode());
                return;
            }
        }
        // 블랙리스트 등 필터가 직접 내린 판정. 던지지 않고 여기서 APIResponse 로 응답하고 체인을 끊는다.
        catch (GeneralException e){
            responder.write(response, e.getErrorCode());
            return;
        }
        catch(JwtException | IllegalArgumentException e){
            log.info("[JWTAuthenticationFilter] - Invalid Refresh Token! ");
            // 인증 정보를 심지 않고 통과시킨다. 보호 자원이면 JwtAuthenticationEntryPoint 가 401 을 낸다.
        }

        filterChain.doFilter(request, response);
    }

    private String resolveAccessToken(HttpServletRequest request){
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7).trim(); // 앞 뒤 공백 제거, "Bearer ~~~" 형식으로 통일
        }
        return null;
    }

    private String resolveRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        return Arrays.stream(cookies)
                .filter(cookie -> "refreshToken".equals(cookie.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElse(null);
    }

    private void handleExpiredAccessToken (HttpServletRequest request, HttpServletResponse response, ExpiredJwtException e){

        Claims claims = e.getClaims();
        String username = claims.getSubject();
        // String authorities = claims.get("auth").toString(); 어차피 userDetails에서 검색하니까 필요 X

        String requestRT = resolveRefreshToken(request);
        String savedRT = redisTemplate.opsForValue().get("refresh:"+username); // 이메일

        if (savedRT == null){
            log.warn("[JWTAuthenticationFilter] - savedRT is null!");
            throw new GeneralException(ErrorCode.CANNOT_FOUND_RT);
        }

        if (requestRT != null && requestRT.equals(savedRT)){
            UserDetails userDetails = customUserDetailsService.loadUserByUsername(username);
            Authentication authentication = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            JWT newJWT = jwtProvider.generateToken(authentication);

            // AT 덮어쓰기
            response.setHeader("Authorization", "Bearer " + newJWT.getAccessToken());

            // RT 덮어쓰기
            ResponseCookie cookie = ResponseCookie.from("refreshToken", newJWT.getRefreshToken())
                    .httpOnly(true)
                    .secure(true)
                    .path("/")
                    .maxAge(7 * 24 * 60 * 60) // 7일
                    .sameSite("Strict")
                    .build();
            response.setHeader(HttpHeaders.SET_COOKIE, cookie.toString());
            // 회전된 RT 도 동일하게 TTL 을 건다 (H-2). 여기가 빠지면 재발급마다 영구 키가 하나씩 쌓인다.
            redisTemplate.opsForValue().set(
                    "refresh:" + username,
                    newJWT.getRefreshToken(),
                    jwtProvider.getRemainingTime(newJWT.getRefreshToken()),
                    TimeUnit.MILLISECONDS);

            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.info("Successfully refreshed token and set security context for user: {}", username);
        }
        else{
            log.warn("[JWTAuthenticationFilter] - requestRT is null! || requestRT is not equal to savedRT!");
            throw new GeneralException(ErrorCode.CANNOT_FOUND_RT);
        }
    }
}
