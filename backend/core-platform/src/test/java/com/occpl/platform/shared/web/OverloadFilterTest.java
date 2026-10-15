package com.occpl.platform.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

class OverloadFilterTest {

    private final OverloadFilter filter = new OverloadFilter(JsonMapper.builder().build());

    @Test
    void poolExhaustionOutsideMvcBecomes503() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), response, (req, res) -> {
            throw new ServletException(new CannotCreateTransactionException("no connection",
                    new SQLTransientConnectionException("core-hikari - Connection is not available")));
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        assertThat(response.getContentAsString()).contains("SERVICE_BUSY").doesNotContain("hikari");
    }

    @Test
    void otherErrorsPassThrough() {
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me"),
                new MockHttpServletResponse(), (req, res) -> {
                    throw new IllegalStateException("boom");
                })).isInstanceOf(IllegalStateException.class);
    }
}
