package Lumo.lumo_backend.global.security.jwt;

import Lumo.lumo_backend.global.security.userDetails.CustomUserDetailsService;
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
import java.util.stream.Collectors;

@Slf4j
@Component
public class JWTProvider {

    // 일단 1시간으로 세팅
    private static final Long ACCESS_TOKEN_EXPIRE_TIME = (long) 1000 * 60 * 60;
    private static final Long REFRESH_TOKEN_EXPIRE_TIME = (long) 1000 * 60 * 60;

    private final Key key;
    private final CustomUserDetailsService customUserDetailsService;


    /** HS256 최소 키 길이. RFC 7518 §3.2 — 키는 해시 출력 길이(256bit) 이상이어야 한다. */
    private static final int MIN_KEY_BYTES = 32;

    public JWTProvider(@Value("${jwt.secret.key}") String key, CustomUserDetailsService customUserDetailsService) {
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
        this.customUserDetailsService = customUserDetailsService; // 커스텀 UserDetailsService 를 통한 DB 조회
    }

    /**
     * 실질적인 JWT 반환 메서드!
     * Service -> login 메서드에서 사용
     * */
    public JWT generateToken (Authentication authentication){
        String authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        String username = authentication.getName();

        long now = (new Date()).getTime();

        Date accessTokenExpire = new Date(now + ACCESS_TOKEN_EXPIRE_TIME);
        String accessToken = createNewToken(username, authorities, accessTokenExpire);

        Date refreshTokenExpire = new Date(now + REFRESH_TOKEN_EXPIRE_TIME);
        String refreshToken = createNewToken(username, authorities, refreshTokenExpire);

        return JWT.builder()
                .grantType("Bearer")
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    public String createNewToken(String email, String authorities, Date expireDate){
        return Jwts.builder()
//                .claim("username", email)
                .subject(email) // 표준 필드로 수정
                .claim("auth", authorities != null ? authorities : "ROLE_USER") // authorities 있는 경우 Member 필드의 Role 반환
                .expiration(expireDate)
                .signWith(key)
                .compact();
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
            log.warn("[JWTProvider-ValidateToken()] : 만료된 토큰입니다 ", e);
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

    public Authentication getAuthentication(String accessToken) {
        Claims claims = parseClaims(accessToken);

        Object authClaimObject = claims.get("auth") != null ? claims.get("auth") : "";

//        log.info("authClaimObject: {}, ", authClaimObject.toString());


        String authoritiesString = (authClaimObject != null) ? authClaimObject.toString() : "";

//        log.info("authoritiesString: {}", authoritiesString);

        if (authoritiesString.isEmpty() || claims.get("auth") == null) {
            ///  GenerationException 으로 수정하기
            throw new RuntimeException("권한 정보가 없는 이상한 토큰입니다");
        }

        // 표준 필드로 변경
//        log.info("[JWTProvider - getAuthentication()] email: {}", claims.get("username", String.class));
        log.info("[JWTProvider - getAuthentication()] email: {}", claims.getSubject());

        UserDetails userDetails = customUserDetailsService.loadUserByUsername(claims.getSubject());
        return new UsernamePasswordAuthenticationToken(userDetails, "", userDetails.getAuthorities());
    }

    private Claims parseClaims(String accessToken) {
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
            ///  GenerationException 으로 수정하기
            throw new RuntimeException("파싱이 잘못되었습니다.");
        }
    }

    public Long getRemainingTime(String token) {
        Claims claims = parseClaims(token);
        return claims.getExpiration().getTime() - System.currentTimeMillis(); // 만료 시간 - 남은 시간
    }
}
