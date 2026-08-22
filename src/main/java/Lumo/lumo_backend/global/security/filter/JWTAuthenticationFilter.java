package Lumo.lumo_backend.global.security.filter;

import Lumo.lumo_backend.global.apiResponse.status.ErrorCode;
import Lumo.lumo_backend.global.exception.GeneralException;
import Lumo.lumo_backend.global.security.jwt.JWT;
import Lumo.lumo_backend.global.security.handler.SecurityErrorResponder;
import Lumo.lumo_backend.global.security.token.RefreshTokenKey;
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

    /** (G-9) 회전 직후 유예 창. 네트워크 재시도·동시 요청을 탈취로 오판하지 않기 위한 최소 구간. */
    private static final long PREV_RT_GRACE_SECONDS = 20;

    /**
     * 인증 필터 전체 소요 시간, 여기만 히스토그램을 켠다 —
     * p95 가 필요한 건 전체뿐이고, 블루-그린 2컨테이너의 분위수를 서버에서 합산하려면
     * publishPercentiles 가 아니라 버킷 기반이어야 한다(전자는 인스턴스 간 합산 불가).
     */
    private Timer filterTimer;

    /** 블랙리스트 Redis 왕복. "제거할 가치가 있는가"를 판단할 근거가 된다. */

    @PostConstruct
    void initMetrics() {
        filterTimer = Timer.builder("auth.filter.duration")
                .description("JWT 인증 필터 전체 소요 시간 (체인 이후는 제외)")
                .publishPercentileHistogram()
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
                // 파싱 1회로 통합한다. 이 Claims 를 typ 검증과 인증 주체 조립에 함께 쓴다.
                Claims claims = jwtProvider.parseClaims(accessToken);

                if (!JWTProvider.TOKEN_TYPE_ACCESS.equals(jwtProvider.getTokenType(claims))) {
                    log.warn("[JWTAuthenticationFilter] - Not an access token");
                    throw new GeneralException(ErrorCode.AUTH_TOKEN_INVALID);
                }

                /*
                 * 블랙리스트 조회 제거
                 * - AT가 1시간이라 로그아웃 후에도 유효하다는 문제 발생
                 * - 15분으로 줄이면 노출 창이 짧아짐 + Redis 왕복 근거가 사라짐
                 * - 어쨌든 15분이라도 유효한 문제가 발생 -> 로그아웃 시 바로 지우는 API가 있어서 재사용 감지 로직이 세션 무력화
                 */

                Authentication authentication = jwtProvider.getAuthentication(claims);
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

        /*
         * (H-9) RT 키를 기기 단위로 분리한다.
         *
         * 이전에는 refresh:{email} 하나뿐이라 기기 B 로그인이 기기 A 의 RT 를 덮어썼고,
         * 그 뒤 기기 A 의 갱신 시도가 아래 재사용 감지에 "탈취" 로 잡혀 두 기기가 함께 죽었다.
         * 키가 기기별로 나뉘면 "저장값과 다르다 = 이미 쓴 RT" 라는 전제가 비로소 참이 된다.
         */
        String deviceId = RefreshTokenKey.resolveDeviceId(request);
        String refreshKey = RefreshTokenKey.refresh(username, deviceId);
        String prevRtKey = RefreshTokenKey.prevRt(username, deviceId);

        String savedRT = redisTemplate.opsForValue().get(refreshKey);

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

        // ------------------RT 재사용 감지-------------------------

        /*
         *
         * 이전 로직은 "저장값과 다르면 거절"이 전부였다. 그러나 회전형 RT 에서 *이미 사용된* RT 가
         * 다시 오는 것은 단순 실패가 아니라 탈취 신호다 — 정상 클라이언트라면 회전 후 새 RT 만
         * 갖고 있기 때문이다. OAuth 2.0 Security BCP 4.14.2 가 권장함.
         *
         * 유예 창(prev_rt)이 필요한 이유: 네트워크 재시도나 동시 요청 두 건이 같은 RT 로 들어오면
         * 정상 사용자도 "재사용"으로 잡혀 강제 로그아웃된다. 직전 RT 를 짧게 남겨 그 구간만 통과시킨다.
         */
        if (requestRT != null && !requestRT.equals(savedRT)) {
            String prevRT = redisTemplate.opsForValue().get(prevRtKey);

            if (requestRT.equals(prevRT)) {
                // 1. 회전 직후의 재시도. 세션을 죽이지 않고 현재 RT 를 쓰라고 알린다.
                log.info("[JWTAuthenticationFilter] - RT rotation grace hit for {}", username);
                throw new GeneralException(ErrorCode.AUTH_TOKEN_EXPIRED);
            }

            /*
             * 2. 유예 창 밖의 불일치 = 탈취로 판정한다.
             *
             * (H-9) 무효화 범위를 "해당 기기" 로 한정한다. 전 기기를 끊는 편이 OAuth BCP 권장에
             * 가깝지만, RT 는 기기별로 발급되므로 한 기기의 RT 가 새도 다른 기기 RT 는 공격자에게 없다.
             * 전 세션 무효화는 과잉이고, H-9 자체가 바로 그 과잉이 만든 회귀였다.
             */
            log.warn("[JWTAuthenticationFilter] - RT REUSE DETECTED. Invalidating session for {} (device={})",
                    username, deviceId);
            redisTemplate.delete(refreshKey);
            redisTemplate.delete(prevRtKey);
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
            // 직전 RT 를 짧게 보관한다. 이 창 안에 들어온 같은 RT 는 재시도로 보고,
            // 3. 창 밖에서 오면 탈취로 판정한다. 창이 길수록 탈취 감지가 무뎌지므로 짧게 잡는다.
            redisTemplate.opsForValue().set(
                    prevRtKey, savedRT, PREV_RT_GRACE_SECONDS, TimeUnit.SECONDS);

            // 회전된 RT 도 동일하게 TTL 을 건다 (H-2). 여기가 빠지면 재발급마다 영구 키가 하나씩 쌓인다.
            redisTemplate.opsForValue().set(
                    refreshKey,
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
