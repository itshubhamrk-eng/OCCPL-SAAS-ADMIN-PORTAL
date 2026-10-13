package com.occpl.platform.identity.api;

import java.util.UUID;

public record UserSummary(UUID id, String email, String displayName, UserStatus status) {
}
