package com.occpl.platform.company.internal;

import com.occpl.platform.company.api.CompanyStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CompanyRepository extends JpaRepository<Company, UUID> {

    boolean existsByCode(String code);

    /** Prefix search on code or display name (case-insensitive collation); {@code q} is a LIKE pattern. */
    @Query("""
            select c from Company c
            where (:status is null or c.status = :status)
              and (:country is null or c.country = :country)
              and (:q is null or c.code like :q escape '\\' or c.displayName like :q escape '\\')""")
    Page<Company> search(CompanyStatus status, String country, String q, Pageable pageable);
}
