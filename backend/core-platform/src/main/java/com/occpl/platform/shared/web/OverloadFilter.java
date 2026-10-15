package com.occpl.platform.shared.web;

import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.error.Overload;
import com.occpl.platform.shared.error.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers 503 with {@code Retry-After} when the database pool is exhausted outside Spring MVC, e.g.
 * while the security filter maps the token to a user (the controller advice handles the rest; see
 * {@link Overload}). Runs right after {@link CorrelationIdFilter}, before Spring Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class OverloadFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OverloadFilter.class);

    private final JsonMapper jsonMapper;

    public OverloadFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException e) {
            if (!Overload.isOverload(e) || response.isCommitted()) {
                throw e;
            }
            log.warn("Overloaded (no database connection available) for {} {}", request.getMethod(),
                    request.getRequestURI());
            response.resetBuffer();
            response.setStatus(503);
            response.setHeader(HttpHeaders.RETRY_AFTER, Overload.RETRY_AFTER_SECONDS);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            jsonMapper.writeValue(response.getOutputStream(),
                    Problems.of(ErrorCode.SERVICE_BUSY, Overload.detail(), request.getRequestURI()));
        }
    }
}
