package Lumo.lumo_backend.global.exception;


import Lumo.lumo_backend.global.apiResponse.APIResponse;
import Lumo.lumo_backend.global.apiResponse.dto.ErrorReasonDTO;
import Lumo.lumo_backend.global.apiResponse.status.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
// jspecify 는 springdoc 을 통해 전이 의존으로 들어오던 라이브러리다.
// swagger 제거 시 컴파일이 깨져 함께 주석 처리한다 (20260813).
// import org.jspecify.annotations.Nullable;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestControllerAdvice(annotations = RestController.class)
public class ExceptionAdvice extends ResponseEntityExceptionHandler {

    @ExceptionHandler
    public ResponseEntity<Object> validation(ConstraintViolationException ex, WebRequest request) {
        String errorMessage = ex.getConstraintViolations().stream()
                .map(constraintViolation -> constraintViolation.getMessage())
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Constraint violation exception 추출 도중 에러 발생"));

        return handleExceptionInternalConstraint(ex, ErrorCode.valueOf(errorMessage), HttpHeaders.EMPTY, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorCode errorCode = ErrorCode.INVALID_JSON;
        APIResponse<Object> body = APIResponse.onFailure(errorCode.getCodeName(), errorCode.getMessage(), null);
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    // @Nullable 제거 — 아래 handleMethodArgumentNotValid 등 다른 오버라이드와도 표기가 일치한다.
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        APIResponse<Object> body = APIResponse.onFailure("TYPE_MISMATCH", "형식이 올바르지 않습니다.", null);
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new HashMap<>();

        ex.getBindingResult().getFieldErrors().stream()
                .forEach(fieldError -> {
                    String field = fieldError.getField();
                    String errorMessage = Optional.ofNullable(fieldError.getDefaultMessage()).orElse("");
                    errors.merge(field, errorMessage, (existingErrorMessage, newErrorMessage) -> existingErrorMessage + ", " + newErrorMessage);
                });

        return handleExceptionInternalArgs(ex, HttpHeaders.EMPTY, ErrorCode.REQUEST_INVALID, request, errors);
    }

    @ExceptionHandler
    public ResponseEntity<Object> exception(Exception ex, WebRequest request) {
        ex.printStackTrace();
        return handleExceptionInternalFalse(ex, ErrorCode.INTERNAL_SERVER_ERROR, HttpHeaders.EMPTY, ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus(), request, ex.getMessage());
    }

    @ExceptionHandler(value = GeneralException.class)
    public ResponseEntity<Object> onThrowException(GeneralException generalEx, HttpServletRequest request) {
        ErrorReasonDTO reasonHttpStatus = generalEx.getReasonHttpStatus();

        log.error("Exception occurred! : {}", reasonHttpStatus.getMessage());

        return handleExceptionInternal(generalEx, reasonHttpStatus, null, request);
    }

    private ResponseEntity<Object> handleExceptionInternal(Exception ex, ErrorReasonDTO reason, HttpHeaders headers, HttpServletRequest request) {
        APIResponse<Object> body = APIResponse.onFailure(reason.getCode(), reason.getMessage(), null);
        WebRequest webRequest = new ServletWebRequest(request);
        return super.handleExceptionInternal(ex, body, headers, reason.getHttpStatus(), webRequest);
    }

    private ResponseEntity<Object> handleExceptionInternalConstraint(Exception ex, ErrorCode errorStatus, HttpHeaders headers, WebRequest request) {
        APIResponse<Object> body = APIResponse.onFailure(errorStatus.getCodeName(), errorStatus.getMessage(), null);
        return super.handleExceptionInternal(ex, body, headers, errorStatus.getHttpStatus(), request);
    }

    private ResponseEntity<Object> handleExceptionInternalArgs(Exception ex, HttpHeaders headers, ErrorCode errorCommonStatus, WebRequest request, Map<String, String> errorArgs) {
        APIResponse<Object> body = APIResponse.onFailure(errorCommonStatus.getCodeName(), errorCommonStatus.getMessage(), errorArgs);
        return super.handleExceptionInternal(ex, body, headers, errorCommonStatus.getHttpStatus(), request);
    }

    private ResponseEntity<Object> handleExceptionInternalFalse(Exception ex, ErrorCode errorCommonStatus, HttpHeaders headers, HttpStatus status, WebRequest request, String errorPoint) {
        APIResponse<Object> body = APIResponse.onFailure(errorCommonStatus.getCodeName(), errorCommonStatus.getMessage(), errorPoint);
        return super.handleExceptionInternal(ex, body, headers, status, request
        );
    }
}