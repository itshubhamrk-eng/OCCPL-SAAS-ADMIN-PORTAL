package com.occpl.platform.catalog.internal;

import com.occpl.platform.catalog.api.ProductStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ProductRepository extends JpaRepository<Product, UUID> {

    boolean existsByCode(String code);

    boolean existsByApiAudienceAndIdNot(String apiAudience, UUID id);

    boolean existsByApiAudience(String apiAudience);

    Optional<Product> findByCode(String code);

    @Query("""
            select p from Product p
            where (:status is null or p.status = :status)
              and (:q is null or p.code like :q escape '\\' or p.name like :q escape '\\')""")
    Page<Product> search(ProductStatus status, String q, Pageable pageable);
}
