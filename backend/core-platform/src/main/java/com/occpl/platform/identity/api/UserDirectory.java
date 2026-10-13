package com.occpl.platform.identity.api;

import java.util.Optional;
import java.util.UUID;

public interface UserDirectory {

    Optional<UserSummary> find(UUID userId);
}
