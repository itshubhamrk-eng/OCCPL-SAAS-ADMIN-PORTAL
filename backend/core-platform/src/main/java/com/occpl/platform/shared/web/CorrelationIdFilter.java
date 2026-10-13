package com.occpl.platform.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a well-formed {@code X-Correlation-Id} from the caller (e.g. Nginx or a product service)
 * or generates one, exposes it in the MDC for structured logs and echoes it on the response.
 * Runs before Spring Security so that 401/403 responses carry the id too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(CorrelationId.HEADER);
        String id = incoming != null && VALID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put(CorrelationId.MDC_KEY, id);
        response.setHeader(CorrelationId.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
