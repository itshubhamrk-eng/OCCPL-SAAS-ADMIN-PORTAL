package com.occpl.platform.access.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Company-scope roles and permissions of memberships. */
public interface CompanyAccess {

    /** Batch lookup to avoid N+1 queries; memberships without roles map to {@link MembershipAccess#NONE}. */
    Map<UUID, MembershipAccess> forMemberships(Collection<UUID> membershipIds);
}
