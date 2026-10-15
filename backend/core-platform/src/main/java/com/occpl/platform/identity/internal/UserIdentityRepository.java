package com.occpl.platform.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    Optional<UserIdentity> findByProviderAndSubject(String provider, String subject);

    Optional<UserIdentity> findByUserIdAndProvider(UUID userId, String provider);
}
