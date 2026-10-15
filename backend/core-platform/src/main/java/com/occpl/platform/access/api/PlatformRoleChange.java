package com.occpl.platform.access.api;

import java.util.Set;

public record PlatformRoleChange(Set<String> added, Set<String> removed, Set<String> current) {

    public boolean changed() {
        return !added.isEmpty() || !removed.isEmpty();
    }
}
