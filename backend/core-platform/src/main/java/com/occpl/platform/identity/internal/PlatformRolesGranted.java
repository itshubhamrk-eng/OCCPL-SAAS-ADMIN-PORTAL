package com.occpl.platform.identity.internal;

import java.util.Set;
import java.util.UUID;

/** Published inside the transaction that granted platform roles to a user. */
record PlatformRolesGranted(UUID userId, Set<String> addedRoles) {
}
