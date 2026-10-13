package com.occpl.platform.shared.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Minimal view of the authenticated principal, available to every module (e.g. audit) without
 * depending on the identity module. The identity module's principal implements it.
 */
public interface Actor {

    UUID userId();

    static Optional<Actor> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Actor actor) {
            return Optional.of(actor);
        }
        return Optional.empty();
    }
}
