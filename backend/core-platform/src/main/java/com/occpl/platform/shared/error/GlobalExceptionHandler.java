package com.occpl.platform.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception raised by controllers to an RFC 9457 problem document. Unexpected errors
 * are logged with their stack trace server-side and returned as a generic 500 without details.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex, HttpServletRequest request) {
        return respond(Problems.of(ex.code(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
            HttpServletRequest request) {
        List<Problems.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new Problems.FieldViolation(v.getPropertyPath().toString(),
                        v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                        v.getMessage()))
                .toList();
        return respond(Problems.validation(request.getRequestURI(), violations));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(HttpServletRequest request) {
        return respond(Problems.of(ErrorCode.PRECONDITION_FAILED,
                "The resource was modified by another request. Reload and retry.", request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(HttpServletRequest request) {
        return respond(Problems.of(ErrorCode.FORBIDDEN, "You do not have permission to perform this action.",
                request.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(HttpServletRequest request) {
        return respond(Problems.of(ErrorCode.UNAUTHENTICATED, "Authentication is required.",
                request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(Problems.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.",
                request.getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Problems.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .toList();
        ProblemDetail problem = Problems.validation(path(request), violations);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** Framework-level errors (404, 405, 415, unreadable body, …) keep their status but use our format. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = switch (statusCode.value()) {
            case 400 -> ErrorCode.VALIDATION_ERROR;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> statusCode.is4xxClientError() ? ErrorCode.VALIDATION_ERROR : ErrorCode.INTERNAL_ERROR;
        };
        if (statusCode.is5xxServerError()) {
            log.error("Framework error for {}", path(request), ex);
        }
        String detail = statusCode.value() == 400 ? "The request could not be processed." : code.title() + ".";
        ProblemDetail problem = Problems.of(code, detail, path(request));
        problem.setStatus(statusCode.value());
        return ResponseEntity.status(statusCode).headers(headers).body(problem);
    }

    private static Problems.FieldViolation toViolation(FieldError error) {
        return new Problems.FieldViolation(error.getField(), error.getCode(), error.getDefaultMessage());
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
    }
}
