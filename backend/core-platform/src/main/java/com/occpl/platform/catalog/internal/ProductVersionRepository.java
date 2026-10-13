package com.occpl.platform.catalog.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ProductVersionRepository extends JpaRepository<ProductVersion, UUID> {

    List<ProductVersion> findByProductIdOrderByVersionNoDesc(UUID productId);

    Optional<ProductVersion> findByProductIdAndVersionNo(UUID productId, int versionNo);

    Optional<ProductVersion> findFirstByProductIdOrderByVersionNoDesc(UUID productId);

    @Query("select coalesce(max(v.versionNo), 0) from ProductVersion v where v.productId = :productId")
    int maxVersionNo(UUID productId);
}
