package com.occpl.platform.membership.internal;

import java.time.Instant;
import java.util.UUID;

/**
 * Published in the transaction that created or re-issued an invitation. Carries the raw token,
 * which exists only in memory and in the outgoing email.
 */
record InvitationIssued(UUID invitationId, UUID companyId, String companyName, String email, String firstName,
        String lastName, String inviterName, String token, Instant expiresAt) {
}
