package Lumo.lumo_backend.domain.member.controller;

import Lumo.lumo_backend.domain.member.dto.MemberReqDTO;
import Lumo.lumo_backend.domain.member.dto.MemberRespDTO;
import Lumo.lumo_backend.domain.member.service.MemberService;
import Lumo.lumo_backend.domain.member.status.MemberSuccessCode;
import Lumo.lumo_backend.global.apiResponse.APIResponse;
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

import static Lumo.lumo_backend.domain.member.status.MemberSuccessCode.VERIFY_CODE_SUCCESS;

@RestController
@Slf4j
@RequestMapping("/api/member")
@RequiredArgsConstructor
// @Tag(name = "사용자 API", description = "회원가입, 로그인, 이메일 인증 관련 API를 담은 사용자 API 입니다.")
public class MemberController {

    private final MemberService memberService;

    @GetMapping("/login")
    // @Operation(summary = "로그인 방식 조회 API", description = "사용자가 로그인한 방식을 조회하는 API 입니다.")
    public APIResponse<MemberRespDTO.GetLoginDTO> getLoginMethod(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return APIResponse.onSuccess(memberService.getLogin(userDetails.getMember()), MemberSuccessCode.GET_LOGIN_SUCCESS); // 로그인 방식 리턴
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
    public APIResponse<Object> logout(HttpServletRequest request, @AuthenticationPrincipal CustomUserDetails userDetails) {

        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            memberService.logout(bearerToken.substring(7).trim(), userDetails.getMember().getId());
        }
        return null; // bool 값 리턴
    }

    @PostMapping("/withdrawl")
    // @Operation(summary = "회원탈퇴 API", description = "로그인을 한 사용자에 한해, 회원탈퇴를 진행하는 API 입니다.")
    public APIResponse<Object> withdrawal() {
        return null; // bool 값 리턴, SOFT DELETE
    }

    @GetMapping("/email-duplicate")
    // @Operation(summary = "이메일 중복 체크 API", description = "회원가입 중 이메일 확잍을 통해 서비스 중복 가입을 방지하는 API 입니다.")
    public APIResponse<MemberRespDTO.SimpleAPIRespDTO> checkEmailDuplicate(@RequestParam("email") String email) {
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
    public APIResponse<MemberRespDTO.FindEmailRespDTO> findEmail(@RequestParam String email) {
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
//        return APIResponse.onSuccess(memberService.getMissionHistory(userDetails.getMember().getId()), MemberSuccessCode.TEST_SUCCESS);
        return null;
    }


}
