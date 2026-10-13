package com.occpl.platform.membership.internal;

import com.occpl.platform.access.api.PlatformRoles;
import com.occpl.platform.company.api.CompanyCreated;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Invites the initial Company Admin inside the company-creation transaction (all-or-nothing). */
@Component
class CompanyCreatedListener {

    private final InvitationService invitations;

    CompanyCreatedListener(InvitationService invitations) {
        this.invitations = invitations;
    }

    @EventListener
    void onCompanyCreated(CompanyCreated event) {
        invitations.invite(event.companyId(), event.initialAdminEmail(), Set.of(PlatformRoles.COMPANY_ADMIN),
                event.initialAdminFirstName(), event.initialAdminLastName());
    }
}
