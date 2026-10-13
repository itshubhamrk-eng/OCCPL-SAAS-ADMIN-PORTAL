package com.occpl.platform.shared.error;

import java.util.List;

/** Several field-level problems at once; rendered as a VALIDATION_ERROR with an {@code errors} array. */
public class ValidationException extends ApiException {

    private final List<Problems.FieldViolation> violations;

    public ValidationException(String detail, List<Problems.FieldViolation> violations) {
        super(ErrorCode.VALIDATION_ERROR, detail);
        this.violations = List.copyOf(violations);
    }

    public List<Problems.FieldViolation> violations() {
        return violations;
    }
}
