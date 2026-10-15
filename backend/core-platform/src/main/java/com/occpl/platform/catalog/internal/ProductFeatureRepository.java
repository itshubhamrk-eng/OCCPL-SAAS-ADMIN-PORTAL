package com.occpl.platform.catalog.internal;

import com.occpl.platform.catalog.api.FeatureStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ProductFeatureRepository extends JpaRepository<ProductFeature, UUID> {

    boolean existsByProductIdAndCode(UUID productId, String code);

    Optional<ProductFeature> findByIdAndProductId(UUID id, UUID productId);

    @Query("select f from ProductFeature f where f.productId = :productId and (:status is null or f.status = :status) order by f.code")
    List<ProductFeature> findByProduct(UUID productId, FeatureStatus status);
}
