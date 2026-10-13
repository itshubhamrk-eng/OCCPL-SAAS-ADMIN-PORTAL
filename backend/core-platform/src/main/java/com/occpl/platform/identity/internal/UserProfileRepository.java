package com.occpl.platform.identity.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {
}
