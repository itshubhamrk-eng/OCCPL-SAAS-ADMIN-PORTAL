package com.occpl.platform.shared.security;

import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.error.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes 401/403 responses raised inside the security filter chain as RFC 9457 documents, while
 * keeping the standard {@code WWW-Authenticate} header from the bearer-token handlers.
 */
class ProblemDetailsAuthenticationHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;
    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerDeniedHandler = new BearerTokenAccessDeniedHandler();

    ProblemDetailsAuthenticationHandlers(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        if (authException instanceof AccountAccessException accountException) {
            write(response, Problems.of(accountException.code(), accountException.getMessage(),
                    request.getRequestURI()));
            return;
        }
        bearerEntryPoint.commence(request, response, authException);
        write(response, Problems.of(ErrorCode.UNAUTHENTICATED, "Authentication is required.",
                request.getRequestURI()));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        bearerDeniedHandler.handle(request, response, accessDeniedException);
        write(response, Problems.of(ErrorCode.FORBIDDEN, "You do not have permission to perform this action.",
                request.getRequestURI()));
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
