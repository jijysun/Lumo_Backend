package Lumo.lumo_backend.domain.member.controller;

import Lumo.lumo_backend.domain.member.dto.MemberReqDTO;
import Lumo.lumo_backend.domain.member.dto.MemberRespDTO;
import Lumo.lumo_backend.domain.member.exception.MemberException;
import Lumo.lumo_backend.domain.member.service.MemberService;
import Lumo.lumo_backend.domain.member.status.MemberErrorCode;
import Lumo.lumo_backend.domain.member.status.MemberSuccessCode;
import Lumo.lumo_backend.global.apiResponse.APIResponse;
import Lumo.lumo_backend.global.ratelimit.IpRateLimiter;
import Lumo.lumo_backend.global.security.userDetails.CustomUserDetails;
// import io.swagger.v3.oas.annotations.Operation;
// import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

import static Lumo.lumo_backend.domain.member.status.MemberSuccessCode.VERIFY_CODE_SUCCESS;

@RestController
@Slf4j
@RequestMapping("/api/member")
@RequiredArgsConstructor
// @Tag(name = "사용자 API", description = "회원가입, 로그인, 이메일 인증 관련 API를 담은 사용자 API 입니다.")
public class MemberController {

    private final MemberService memberService;
    private final IpRateLimiter ipRateLimiter;

    /*
     * (M-7) 사용자 열거 대응.
     * email-duplicate / find-email 은 "이 이메일이 가입돼 있는가"를 그대로 알려준다.
     * 회원가입 UX 상 응답을 감출 수 없으므로, 같은 출처가 대량으로 훑는 것을 막는다.
     * 이메일 단위로 세면 이메일을 바꿔가며 우회되므로 반드시 IP 기준이어야 한다.
     */
    private static final int LOOKUP_LIMIT = 20;
    private static final Duration LOOKUP_WINDOW = Duration.ofMinutes(10);

    @GetMapping("/login")
    // @Operation(summary = "로그인 방식 조회 API", description = "사용자가 로그인한 방식을 조회하는 API 입니다.")
    public APIResponse<MemberRespDTO.GetLoginDTO> getLoginMethod(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return APIResponse.onSuccess(memberService.getLogin(userDetails.getUsername()), MemberSuccessCode.GET_LOGIN_SUCCESS); // 로그인 방식 리턴
    }

    @PostMapping("/login")
    // @Operation(summary = "로그인 API", description = "닉네임과 비밀번호로 로그인을 진행합니다. 성공 여부와 JWT accessToken을 반환합니다. 쿠키로는 RefreshToken를 설정하도록 하였습니다. ")
    public APIResponse<MemberRespDTO.LoginRespDTO> reqLogin(@RequestBody MemberReqDTO.LoginReqDTO dto, HttpServletResponse response) {

        MemberRespDTO.MemberInfoDTO memberInfo = memberService.login(dto);

        ResponseCookie cookie = ResponseCookie.from("refreshToken", memberInfo.getJwt().getRefreshToken())
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(7 * 24 * 60 * 60) // 7일
                .sameSite("Strict")
                .build();
        response.setHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        MemberRespDTO.LoginRespDTO respDTO = MemberRespDTO.LoginRespDTO.builder().username(memberInfo.getUsername()).isSuccess(true).accessToken(memberInfo.getJwt().getAccessToken()).build();
        return APIResponse.onSuccess(respDTO, MemberSuccessCode.LOGIN_SUCCESS);
    }

    @PostMapping("/logout")
    // @Operation(summary = "로그아웃 API", description = "로그인을 한 사용자에 한해, 로그아웃을 진행하는 API 입니다.")
    public APIResponse<MemberRespDTO.SimpleAPIRespDTO> logout(HttpServletRequest request, @AuthenticationPrincipal CustomUserDetails userDetails) {

        // (M-6) 로그아웃은 "미구현"이 아니라 구현돼 있는데 null 을 반환하던 것이다.
        // Authorization 헤더가 없으면 아무 일도 안 하고 성공처럼 보이던 것도 함께 막는다.
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
            throw new MemberException(MemberErrorCode.CANT_FOUND_MEMBER);
        }

        memberService.logout(bearerToken.substring(7).trim(), userDetails.getMemberId());

        return APIResponse.onSuccess(
                MemberRespDTO.SimpleAPIRespDTO.builder().isSuccess(true).build(),
                MemberSuccessCode.LOGOUT_SUCCESS);
    }

    // (M-6) 회원탈퇴 — 미구현이고 구현 예정도 없다. 매핑만 주석 처리해 엔드포인트에서 제외한다.
    //       구현 시 @PostMapping 을 되살리고 SOFT DELETE 로 작성할 것.
//    @PostMapping("/withdrawl")
    // @Operation(summary = "회원탈퇴 API", description = "로그인을 한 사용자에 한해, 회원탈퇴를 진행하는 API 입니다.")
    public APIResponse<Object> withdrawal() {
        return null; // bool 값 리턴, SOFT DELETE
    }

    @GetMapping("/email-duplicate")
    // @Operation(summary = "이메일 중복 체크 API", description = "회원가입 중 이메일 확잍을 통해 서비스 중복 가입을 방지하는 API 입니다.")
    public APIResponse<MemberRespDTO.SimpleAPIRespDTO> checkEmailDuplicate(@RequestParam("email") String email,
                                                                           HttpServletRequest request) {
        ipRateLimiter.check(request, "email-duplicate", LOOKUP_LIMIT, LOOKUP_WINDOW);

        MemberRespDTO.SimpleAPIRespDTO dto = MemberRespDTO.SimpleAPIRespDTO.builder().isSuccess(memberService.checkEmailDuplicate(email)).build();
        return APIResponse.onSuccess(dto, MemberSuccessCode.EMAIL_DUPLICATE_CHECK_SUCCESS);
    }

    @PostMapping("/request-code")
    // @Operation(summary = "이메일 인증 코드 API", description = "회원가입 중 사용자의 악의적인 회원가입 방지를 위해 인증 코드를 발행하는 API 입니다.")
    public APIResponse<MemberRespDTO.SimpleAPIRespDTO> requestVerificationCode(@RequestParam("email") String email) {
        memberService.requestVerificationCode(email);
        return APIResponse.onSuccess(MemberRespDTO.SimpleAPIRespDTO.builder().isSuccess(true).build(), MemberSuccessCode.REQ_CODE_SUCCESS); // bool 값 리턴,;
    }

    @PostMapping("/verify-code")
    // @Operation(summary = "인증 코드 검증 API", description = "회원가입 중 요청한 인증 코드를 통해 이메일을 인증하는 API 입니다.")
    public APIResponse<Object> verifyCode(@RequestParam("email") String email, @RequestParam("code") String code) {
        memberService.verifyCode(email, code);
        return APIResponse.onSuccess(MemberRespDTO.SimpleAPIRespDTO.builder().isSuccess(true).build(), VERIFY_CODE_SUCCESS);
    }

    @PostMapping("/signin")
    // @Operation(summary = "회원가입 API", description = "이메일 중복 체크, 이메일 인증 코드 검증 이후 최종적으로 사용자가 입력한 정보를 바탕으로 회원가입을 요청하는 API 입니다.")
    public APIResponse<MemberRespDTO.SimpleAPIRespDTO> signIn(@RequestBody MemberReqDTO.SignInRequestDTO dto) {
        memberService.signIn(dto);
        return APIResponse.onSuccess(MemberRespDTO.SimpleAPIRespDTO.builder().isSuccess(true).build(), MemberSuccessCode.SIGN_IN_SUCCESS); // bool 값 리턴,
    }

    @PostMapping("/find-email")
    // @Operation(summary = "비밀번호 재설정 대상 이메일 검색 API", description = "비밀번호를 재설정할 이메일을 찾는 API 입니다")
    public APIResponse<MemberRespDTO.FindEmailRespDTO> findEmail(@RequestParam String email,
                                                                 HttpServletRequest request) {
        ipRateLimiter.check(request, "find-email", LOOKUP_LIMIT, LOOKUP_WINDOW);

        return APIResponse.onSuccess(memberService.findEmail(email), MemberSuccessCode.FIND_EMAIL_SUCCESS);
    }

    @PatchMapping("/change-pw")
    // @Operation(summary = "비밀번호 재설정 API", description = "비밀번호 재설정하는 API 입니다.")
    public APIResponse<MemberRespDTO.SimpleAPIRespDTO> changePassword(@RequestParam String email, @RequestParam("password") String newPassword) {
        return APIResponse.onSuccess(memberService.changePassword(email, newPassword), MemberSuccessCode.CHANGE_PW_SUCCESS);
    }


    //    @GetMapping("/mission-history")
//    @Operation(summary = "내 미션 수행 기록 조회", description = "사용자가 진행했던 미션의 기록을 모두 확인하는 API 입니다.")
    public APIResponse<Object> getMissionHistory(@AuthenticationPrincipal CustomUserDetails userDetails) {
//        return APIResponse.onSuccess(memberService.getMissionHistory(userDetails.getMemberId()), MemberSuccessCode.TEST_SUCCESS);
        return null;
    }


}
