package com.occpl.platform.membership.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The company a request was authorised for, set by {@code @tenant.check(...)} in
 * {@code @PreAuthorize}. {@code membershipId} is null when access was granted through a platform
 * permission (e.g. a Super Admin acting on a company).
 */
public record TenantContext(UUID companyId, UUID membershipId, Set<String> permissions) {

    static final String ATTRIBUTE = TenantContext.class.getName();

    public static Optional<TenantContext> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes == null ? Optional.empty()
                : Optional.ofNullable((TenantContext) attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
    }

    public static void set(TenantContext context) {
        RequestAttributes attributes = RequestContextHolder.currentRequestAttributes();
        attributes.setAttribute(ATTRIBUTE, context, RequestAttributes.SCOPE_REQUEST);
    }
}
