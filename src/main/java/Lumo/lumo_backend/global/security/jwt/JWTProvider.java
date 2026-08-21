package Lumo.lumo_backend.global.security.jwt;

import Lumo.lumo_backend.global.apiResponse.status.ErrorCode;
import Lumo.lumo_backend.global.exception.GeneralException;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetails;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import io.jsonwebtoken.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.Key;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
public class JWTProvider {

    /*
     * AT 와 RT 를 실제로 구분
     * - 이전에는 두 토큰이 같은 서명키·같은 클레임(sub, auth)·같은 만료(1시간)로 발급됐다.
     * - 이러면 RT/AT 구분 없이 인증 관련 API가 통과... (RT에 AT 넣어도 통과)
     *
     * AT 수명 단축(15분)은 여기서 하지 않는다. 그건 G-8 이고 블랙리스트 부담이 바뀌는
     *    측정 대상이라, 함께 넣으면 Phase B 에서 기여도가 섞인다.
     */
    /*
     * (20260821 G-8) AT 1시간 -> 15분.
     *
     * 블랙리스트는 "AT 수명이 길다"는 문제를 매 요청 Redis 왕복으로 땜질한 것이었다.
     * A-1 계측 기준 그 왕복이 인증 필터 시간의 26.2%, G-6 로 DB 조회가 사라진 뒤에는 59.8% 로
     * 최대 몫이 됐다. 근본 처방은 조회를 최적화하는 것이 아니라 수명을 줄여 블랙리스트 자체를
     * 불필요하게 만드는 것이다.
     *
     * 대가: 재발급 빈도가 4배가 된다(1시간 -> 15분). 재발급 경로는 DB 1회 + Redis 2~3회로
     *      인증 경로보다 비싸므로, "AT 를 무조건 짧게"가 아니라 균형점이 있다. 15분은 출발점이고
     *      Phase B 에서 재발급 빈도와 인증 지연을 함께 측정해 조정한다.
     */
    private static final Long ACCESS_TOKEN_EXPIRE_TIME = (long) 1000 * 60 * 15;          // 15분
    private static final Long REFRESH_TOKEN_EXPIRE_TIME = (long) 1000 * 60 * 60 * 24 * 7; // 7일 — 쿠키 maxAge 와 일치

    /** 토큰 종류 Claim. 이 값이 없거나 기대와 다르면 해당 경로에서 거부 */
    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    /*
     * 회원 PK 클레임.
     *
     * C-3 시점에는 아무도 읽지 않는다. 그런데도 지금 심는 이유는 G-6(클레임 확장) 때문이다.
     * 클레임 스키마가 바뀌면 기존 발급 토큰이 전부 무효가 되어 전 사용자가 재로그인해야 하는데,
     * C-3 와 G-6 에서 각각 바꾸면 그 일이 두 번 일어난다. 클레임 "추가"는 하위호환이므로
     * 지금 넣어두고 G-6 에서 읽기만 시작한다.
     */
    public static final String CLAIM_MEMBER_ID = "mid";

    private final Key key;


    /** HS256 최소 키 길이. RFC 7518 §3.2 — 키는 해시 출력 길이(256bit) 이상이어야 한다. */
    private static final int MIN_KEY_BYTES = 32;

    public JWTProvider(@Value("${jwt.secret.key}") String key) {
        /*
         * (20260816 C-4 수정) Base64 "인코딩" → "디코딩".
         *
         * 이전 코드는 Base64.getEncoder().encode(key.getBytes()) 였다.
         * 인코딩은 엔트로피를 늘리지 않고 길이만 4/3배로 부풀린다. 원문이 24바이트(192bit)여도
         * 인코딩 후 32바이트가 되어 Keys.hmacShaKeyFor() 의 최소 길이 검증을 "형식적으로" 통과했고,
         * 실제 키 강도는 192bit 그대로였다. 즉 약한 키 검증을 스스로 우회하는 코드였다.
         *
         * jjwt 가 문서화한 사용법은 Decoders.BASE64.decode(secret) 이다.
         * → 시크릿을 Base64 문자열로 보관하고 원래 바이트로 되돌려 쓴다.
         *
         * ⚠️ JWT_SECRET_KEY 는 반드시 "32바이트 이상 난수의 Base64 문자열"이어야 한다.
         *    생성: openssl rand -base64 32
         */
        byte[] keyBytes = Decoders.BASE64.decode(key);

        // hmacShaKeyFor 의 WeakKeyException 메시지만으로는 원인을 알기 어려워 먼저 걸러낸다.
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret.key 는 Base64 디코딩 후 " + MIN_KEY_BYTES + "바이트(256bit) 이상이어야 합니다. "
                            + "현재 " + keyBytes.length + "바이트(" + (keyBytes.length * 8) + "bit). "
                            + "생성 방법: openssl rand -base64 32");
        }

        this.key = Keys.hmacShaKeyFor(keyBytes);
        // (G-6) CustomUserDetailsService 의존성을 제거했다. 인증 경로가 DB 를 타지 않는다는 사실을
        // 타입 수준에서 강제하기 위해서다 — 필드가 남아 있으면 언제든 다시 호출될 수 있다.
    }

    /**
     * 실질적인 JWT 반환 메서드!
     * Service -> login 메서드에서 사용
     * */
    public JWT generateToken (Authentication authentication, Long memberId){
        String authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        String username = authentication.getName();

        long now = (new Date()).getTime();

        String accessToken = createAccessToken(username, authorities, memberId,
                new Date(now + ACCESS_TOKEN_EXPIRE_TIME));
        String refreshToken = createRefreshToken(username, authorities, memberId,
                new Date(now + REFRESH_TOKEN_EXPIRE_TIME));

        return JWT.builder()
                .grantType("Bearer")
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    public String createAccessToken(String email, String authorities, Long memberId, Date expireDate){
        return buildToken(email, authorities, memberId, expireDate, TOKEN_TYPE_ACCESS);
    }

    public String createRefreshToken(String email, String authorities, Long memberId, Date expireDate){
        return buildToken(email, authorities, memberId, expireDate, TOKEN_TYPE_REFRESH);
    }

    private String buildToken(String email, String authorities, Long memberId, Date expireDate, String tokenType){
        return Jwts.builder()
                /*
                 * JWT 는 같은 클레임이면 같은 바이트 -> sub·auth·typ·mid·exp 만으로 만들면 exp 의 해상도가 "초"이므로 같은 초에 발급된 두 토큰이 완전히 동일해지는 문제 발생.
                 * - 그러면 RTR 회전이 사실상 no-op 가 되어(새 RT == 옛 RT) 재사용 감지가 성립 X (0821 로컬 테스트 확인)
                 *
                 * = 토큰마다 고유한 jti 를 넣어 발급 시점과 무관하게 유일성을 보장!
                 */
                .id(UUID.randomUUID().toString()) // jti
                .subject(email) // 표준 필드로 수정
                .claim("auth", authorities != null ? authorities : "ROLE_USER") // authorities 있는 경우 Member 필드의 Role 반환
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .claim(CLAIM_MEMBER_ID, memberId) // G-6 대비 선반영 — 지금은 아무도 읽지 않는다
                .expiration(expireDate)
                .signWith(key)
                .compact();
    }

    /**
     * 토큰의 종류를 반환한다. 서명·형식이 깨졌거나 만료됐으면 {@link GeneralException} 을 던진다.
     *
     * C-3 이전에 발급된 토큰에는 typ 클레임이 없어 {@code null} 이 반환된다.
     * 호출부는 이를 "유효하지 않은 토큰"으로 취급해야 한다 — 배포 시점의 기존 토큰은 전부 무효다.
     */
    public String getTokenType(String token){
        Object type = parseClaims(token).get(CLAIM_TOKEN_TYPE);
        return type != null ? type.toString() : null;
    }

    /** 만료된 토큰의 클레임에서 종류를 읽는다. 만료 자체는 정상 흐름이므로 예외를 던지지 않는다. */
    public String getTokenType(Claims claims){
        Object type = claims.get(CLAIM_TOKEN_TYPE);
        return type != null ? type.toString() : null;
    }


    public boolean validateToken(String accessToken) {
        try{
            Jwts.parser()
                    .verifyWith((SecretKey) key)
                    .build()
                    .parseSignedClaims(accessToken);
            return true;
        }
        catch (SecurityException | MalformedJwtException e){
            log.warn("[JWTProvider-ValidateToken()] : 잘못된 토큰입니다 ", e);
        }
        catch (ExpiredJwtException e){ // 실패 응답을 통한 로그인 요청 로직
            // 만료 토큰마다 스택트레이스 전문이 찍혀서 레벨만 낮추기
            log.debug("[JWTProvider-ValidateToken()] : 만료된 토큰입니다");
            throw e;
        }
        catch (UnsupportedJwtException e) {
            log.warn("[JWTProvider-ValidateToken()] : 지원되지 않는 토큰 형식입니다 ", e);
        }
        catch (IllegalArgumentException e) {
            log.warn("[JWTProvider-ValidateToken()] : JWT claims String이 비어있습니다. ", e);
        }
        return false;
    }

    /**
     * (20260821 G-6) 클레임만으로 인증 주체를 조립한다. <b>DB 를 치지 않는다.</b>
     *
     * <p>이전에는 여기서 {@code customUserDetailsService.loadUserByUsername()} 을 호출해
     * 인증된 <b>모든 요청</b>이 {@code findByEmail} 을 한 번씩 탔다. JWT 가 이미 sub(email)와
     * auth(권한)를 담고 있는데도 DB 를 다시 물어보는, stateless 의 이점을 스스로 버리는 구조였다.
     * A-1 계측 기준 그 조회가 인증 필터 전체 시간의 <b>61.7%</b>(2.397ms / 3.884ms) 였다.
     *
     * <p><b>대가</b>: 탈퇴·권한 변경·정지가 DB 에 반영돼도 <b>AT 수명(현재 1시간) 동안은
     * 인증 경로가 알지 못한다.</b> 이것이 G-8(AT 수명 단축)의 근거다. 다만 재발급 경로
     * ({@code JWTAuthenticationFilter.handleExpiredAccessToken})는 여전히 DB 를 조회하므로,
     * 반영 지연의 상한은 AT 수명으로 묶인다.
     *
     * <p>개선 확인: {@code auth.userdetails.load} 의 {@code _count} 가 인증 요청에서는 더 이상
     * 증가하지 않고 로그인·재발급에서만 오르면 성공이다.
     */
    public Authentication getAuthentication(String accessToken) {
        return getAuthentication(parseClaims(accessToken));
    }

    /** 이미 파싱한 Claims 를 재사용하는 경로. 필터가 메서드 사용 */
    public Authentication getAuthentication(Claims claims) {

        Object authClaim = claims.get("auth");
        String authoritiesString = (authClaim != null) ? authClaim.toString() : "";

        if (authoritiesString.isEmpty()) {
            // 맨 RuntimeException 은 필터의 catch(GeneralException) 에 걸리지 않아
            // 그대로 컨테이너까지 전파돼 500 HTML 이 나갔다 (H-1).
            throw new GeneralException(ErrorCode.AUTH_TOKEN_INVALID);
        }

        Object memberIdClaim = claims.get(CLAIM_MEMBER_ID);
        if (memberIdClaim == null) {
            // C-3 이전에 발급된 토큰에는 mid 가 없다. 유효하지 않은 토큰으로 취급해 재로그인을 유도한다.
            throw new GeneralException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        // jackson 역직렬화 결과가 Integer 일 수 있어 Number 로 받는다.
        Long memberId = ((Number) memberIdClaim).longValue();

        // DB 조회가 아닌, 직접 CustomUserDetails 조립
        UserDetails userDetails = CustomUserDetails.fromClaims(memberId, claims.getSubject(), authoritiesString);
        return new UsernamePasswordAuthenticationToken(userDetails, "", userDetails.getAuthorities());
    }

    /**
     * (20260821) private -> public.
     *
     * C-3 로 typ 검증이 들어가면서 요청 1건당 파싱이 3회(validateToken · getTokenType ·
     * getAuthentication)로 늘었다. HMAC 검증은 공짜가 아니고 이 경로는 인증된 모든 요청이 탄다.
     * 호출부가 한 번만 파싱하고 Claims 를 재사용하도록 열어둔다.
     */
    public Claims parseClaims(String accessToken) {
        try{
            Claims claims = Jwts.parser()
                    .verifyWith((SecretKey) key)
                    .build()
                    .parseSignedClaims(accessToken)
                    .getPayload();

//            log.info ("[JWTProvider - parseClaims()] claims 파싱 발생 -> {}, {}", claims.toString(), claims.getSubject());
            return claims;
        }
        catch (Exception e){
            // 위와 동일 — 파싱 실패도 APIResponse 형식의 401 로 나가야 한다 (H-1).
            throw new GeneralException(ErrorCode.AUTH_TOKEN_INVALID);
        }
    }

    public Long getRemainingTime(String token) {
        Claims claims = parseClaims(token);
        return claims.getExpiration().getTime() - System.currentTimeMillis(); // 만료 시간 - 남은 시간
    }
}
