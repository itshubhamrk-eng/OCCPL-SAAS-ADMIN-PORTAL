package com.occpl.platform.identity.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface UserDirectory {

    Optional<UserSummary> find(UUID userId);

    /** Batch lookup; unknown ids are absent from the result. */
    Map<UUID, UserSummary> findAll(Collection<UUID> userIds);

    /**
     * Returns the user with this email, creating an INVITED user if none exists. Rejects disabled
     * users. Must run inside the caller's transaction.
     */
    UserSummary findOrCreateInvited(String email);
}
