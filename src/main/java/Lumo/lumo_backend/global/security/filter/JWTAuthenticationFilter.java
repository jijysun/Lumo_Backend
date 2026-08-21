package Lumo.lumo_backend.global.security.filter;

import Lumo.lumo_backend.global.apiResponse.status.ErrorCode;
import Lumo.lumo_backend.global.exception.GeneralException;
import Lumo.lumo_backend.global.security.jwt.JWT;
import Lumo.lumo_backend.global.security.handler.SecurityErrorResponder;
import Lumo.lumo_backend.global.security.jwt.JWTProvider;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetails;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetailsService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
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
    private final MeterRegistry meterRegistry;

    /**
     * 인증 필터 전체 소요 시간, 여기만 히스토그램을 켠다 —
     * p95 가 필요한 건 전체뿐이고, 블루-그린 2컨테이너의 분위수를 서버에서 합산하려면
     * publishPercentiles 가 아니라 버킷 기반이어야 한다(전자는 인스턴스 간 합산 불가).
     */
    private Timer filterTimer;

    /** 블랙리스트 Redis 왕복. "제거할 가치가 있는가"를 판단할 근거가 된다. */
    private Timer blacklistTimer;

    @PostConstruct
    void initMetrics() {
        filterTimer = Timer.builder("auth.filter.duration")
                .description("JWT 인증 필터 전체 소요 시간 (체인 이후는 제외)")
                .publishPercentileHistogram()
                .register(meterRegistry);

        blacklistTimer = Timer.builder("auth.blacklist.lookup")
                .description("블랙리스트 Redis 조회 소요 시간")
                .register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String accessToken = resolveAccessToken(request);

        // 토큰이 없는 요청(헬스체크·정적 리소스 등)은 인증 구간 자체가 없다.
        // 계측에 포함하면 0ms 표본이 대량으로 섞여서 오염됨
        if (accessToken == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        boolean proceed;
        try {
            proceed = authenticate(request, response, accessToken);
        } finally {
            // 오류 응답으로 끝나는 경로도 인증 구간은 소비했으므로 함께 기록
            sample.stop(filterTimer);
        }

        if (proceed) {
            filterChain.doFilter(request, response);
        }
    }

    /**
     * 실제 인증 처리. 계측 범위를 filterChain.doFilter() 이후와 분리하기 위해 메서드로 분리.
     *
     * @return {@code true} 면 필터 체인을 계속 진행, false면 이미 오류 응답을 썼으므로 중단
     */
    private boolean authenticate(HttpServletRequest request, HttpServletResponse response, String accessToken)
            throws IOException {

        /// jwtProvider 에서 인증 조회 + 토큰 검증이 필요!
        try{
            if(jwtProvider.validateToken(accessToken)){ // 올바르지 않거나

                /*
                 * 인증에는 AT 만 허용
                 *
                 * 이전에는 종류를 구분하지 않아 RT 를 Authorization 헤더에 넣으면 그대로 인증됨을 확인
                 * typ 클레임을 심는 것만으로는 아무것도 막지 못한다 — 실제로 닫히는 지점은 여기다.
                 *
                 * C-3 이전에 발급된 토큰은 typ 이 없어 null 이고, 여기서 전부 거부.
                 */
                if (!JWTProvider.TOKEN_TYPE_ACCESS.equals(jwtProvider.getTokenType(accessToken))) {
                    log.warn("[JWTAuthenticationFilter] - Not an access token");
                    throw new GeneralException(ErrorCode.AUTH_TOKEN_INVALID);
                }

                Timer.Sample blacklistSample = Timer.start(meterRegistry);
                String isBlackListed;
                try {
                    isBlackListed = redisTemplate.opsForValue().get("blacklist:" + accessToken);
                } finally {
                    blacklistSample.stop(blacklistTimer);
                }
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
                return false;
            }
        }
        // 블랙리스트 등 필터가 직접 내린 판정. 던지지 않고 여기서 APIResponse 로 응답하고 체인을 끊는다.
        catch (GeneralException e){
            responder.write(response, e.getErrorCode());
            return false;
        }
        catch(JwtException | IllegalArgumentException e){
            log.info("[JWTAuthenticationFilter] - Invalid Refresh Token! ");
            // 인증 정보를 심지 않고 통과시킨다. 보호 자원이면 JwtAuthenticationEntryPoint 가 401 을 낸다.
        }

        return true;
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

        /*
         * (20260821 C-3) 재발급에는 RT 만 허용한다.
         *
         * 문자열 일치만 보던 이전 로직은 "저장된 값과 같은가"만 확인했다. 두 토큰이 구분되지 않던
         * 시절에는 그것으로 충분해 보였지만, 종류를 나눈 이상 여기서도 확인해야 대칭이 맞는다.
         * 만료·변조된 RT 는 parseClaims 가 GeneralException 을 던져 401 로 나간다(재로그인 유도).
         */
        if (requestRT != null && !JWTProvider.TOKEN_TYPE_REFRESH.equals(jwtProvider.getTokenType(requestRT))) {
            log.warn("[JWTAuthenticationFilter] - Not a refresh token");
            throw new GeneralException(ErrorCode.AUTH_TOKEN_INVALID);
        }

        if (requestRT != null && requestRT.equals(savedRT)){
            UserDetails userDetails = customUserDetailsService.loadUserByUsername(username);
            Authentication authentication = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            // C-3 로 generateToken 이 memberId 를 요구한다. 이 경로는 방금 DB 에서 회원을 읽었으므로
            // 바로 꺼낼 수 있다. (G-6 이후에는 클레임의 mid 를 그대로 넘기게 된다)
            Long memberId = ((CustomUserDetails) userDetails).getMember().getId();
            JWT newJWT = jwtProvider.generateToken(authentication, memberId);

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
