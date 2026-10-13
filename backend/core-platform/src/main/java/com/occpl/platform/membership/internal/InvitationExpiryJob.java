package com.occpl.platform.membership.internal;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expires overdue invitations every 15 minutes (acceptance also checks expiry, so this is housekeeping). */
@Component
class InvitationExpiryJob {

    private final InvitationService invitations;

    InvitationExpiryJob(InvitationService invitations) {
        this.invitations = invitations;
    }

    @Scheduled(fixedDelayString = "${occpl.invitations.expiry-interval:15m}", initialDelayString = "1m")
    void run() {
        invitations.expireOverdue();
    }
}
