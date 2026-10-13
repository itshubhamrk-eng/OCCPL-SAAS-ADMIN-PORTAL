package com.occpl.platform.identity.internal;

import java.util.UUID;

/** Published when a user is disabled or re-enabled; synchronised to Keycloak after commit. */
record UserAccessChanged(UUID userId, boolean enabled) {
}
