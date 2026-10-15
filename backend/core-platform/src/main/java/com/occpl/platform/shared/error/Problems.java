package com.occpl.platform.shared.error;

import com.occpl.platform.shared.web.CorrelationId;
import java.net.URI;
import java.util.List;
import org.springframework.http.ProblemDetail;

/** Builds the platform's RFC 9457 problem documents with {@code code} and {@code correlationId}. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        problem.setProperty("code", code.name());
        CorrelationId.current().ifPresent(id -> problem.setProperty("correlationId", id));
        return problem;
    }

    public static ProblemDetail validation(String instance, List<FieldViolation> violations) {
        ProblemDetail problem = of(ErrorCode.VALIDATION_ERROR, "One or more fields are invalid.", instance);
        problem.setProperty("errors", violations);
        return problem;
    }

    public record FieldViolation(String field, String code, String message) {
    }
}
