package com.occpl.platform.identity.internal;

import com.occpl.platform.identity.api.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PlatformUserRepository extends JpaRepository<PlatformUser, UUID> {

    Optional<PlatformUser> findByEmail(String normalizedEmail);

    boolean existsByEmail(String normalizedEmail);

    /**
     * Keyset page ordered by id (UUIDv7, i.e. creation order); {@code after} is the last id of the
     * previous page or null. {@code q} is an email LIKE prefix pattern or null.
     */
    @Query("""
            select u from PlatformUser u
            where (:after is null or u.id > :after)
              and (:status is null or u.status = :status)
              and (:q is null or u.email like :q escape '\\')
            order by u.id""")
    List<PlatformUser> page(UUID after, UserStatus status, String q, Limit limit);
}
