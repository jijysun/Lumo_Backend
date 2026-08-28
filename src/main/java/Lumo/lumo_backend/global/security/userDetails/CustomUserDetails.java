package Lumo.lumo_backend.global.security.userDetails;

import Lumo.lumo_backend.domain.member.entity.Member;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 인증 주체.
 *
 * <p>(20260821 G-6) 이전에는 {@code Member} 엔티티 하나만 들고 있었다. 즉 <b>엔티티가 없으면
 * 만들 수 없는 구조</b>였고, 그래서 인증된 모든 요청이 {@code findByEmail} 로 DB 를 한 번씩 쳤다.
 * A-1 계측 결과 그 조회가 인증 필터 전체 시간의 <b>61.7%</b> 였다.
 *
 * <p>이제 생성 경로가 둘이다.
 * <ul>
 *   <li><b>로그인</b> — DB 에서 읽은 {@code Member} 로 만든다. 비밀번호 검증에 원본이 필요하다.</li>
 *   <li><b>인증 필터</b> — JWT 클레임(sub · auth · mid)만으로 만든다. <b>DB 를 치지 않는다.</b></li>
 * </ul>
 *
 * <p>⚠️ 후자에서는 {@link #getMember()} 가 {@code null} 이다. 컨트롤러가 엔티티를 필요로 하면
 * {@link #getMemberId()} 로 받아 서비스 계층에서 조회해야 한다.
 */
@Getter
public class CustomUserDetails implements UserDetails {

    private final Long memberId;
    private final String email;

    /** 권한 문자열. JWT 의 auth 클레임과 같은 형식(콤마 구분)이다. */
    private final String authorities;

    /** 로그인 경로에서만 채워진다. 인증 필터 경로에서는 null. */
    private final String password;

    /** 로그인 경로에서만 채워진다. 인증 필터 경로에서는 <b>null</b>. */
    private final Member member;

    /**
     * 로그인 경로 — {@link CustomUserDetailsService} 가 DB 에서 읽은 회원으로 만든다.
     * {@code CustomUserDetails::new} 메서드 참조가 이 생성자를 가리킨다.
     */
    public CustomUserDetails(Member member) {
        this.member = member;
        this.memberId = member.getId();
        this.email = member.getEmail();
        this.authorities = member.getRole().name();
        this.password = member.getPassword();
    }

    private CustomUserDetails(Long memberId, String email, String authorities) {
        this.member = null;
        this.memberId = memberId;
        this.email = email;
        this.authorities = authorities;
        this.password = null;
    }

    /** 인증 필터 경로 — 클레임만으로 조립한다. DB 왕복 0회 (G-6). */
    public static CustomUserDetails fromClaims(Long memberId, String email, String authorities) {
        return new CustomUserDetails(memberId, email, authorities);
    }

    /*
     * isAccountNonExpired(), isAccountNonLocked(), isCredentialsNonExpired(), isEnabled()
     * -> 서비스 상 관련 기획이 존재하지 않으므로 return true로 변경.
     */

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // auth 클레임은 콤마 구분 문자열이다(JWTProvider.generateToken).
        // 현재는 권한이 하나뿐이지만 형식을 그대로 따라 분해한다.
        return Arrays.stream(authorities.split(","))
                .map(String::trim)
                .filter(a -> !a.isEmpty())
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    /**
     * ⚠️ 인증 필터를 통과한 요청에서는 <b>null</b> 이다 (G-6).
     * 엔티티가 필요하면 {@link #getMemberId()} 를 서비스로 넘겨 조회한다.
     */
    public Member getMember() {
        return member;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email; // 고유 값인 이메일 반환!!
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
