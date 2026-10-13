package com.occpl.platform.access.api;

import java.util.Set;

/** Roles and the resulting permissions of one company membership. */
public record MembershipAccess(Set<String> roleCodes, Set<String> permissions) {

    public static final MembershipAccess NONE = new MembershipAccess(Set.of(), Set.of());
}
