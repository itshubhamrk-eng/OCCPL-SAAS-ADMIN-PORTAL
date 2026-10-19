package com.occpl.platform.lead.internal;

import com.occpl.platform.lead.api.LeadStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface LeadRepository extends JpaRepository<Lead, UUID> {

    @Query("""
            select l from Lead l
            where (:status is null or l.status = :status)
              and (:q is null or l.companyName like :q escape '\\' or l.contactName like :q escape '\\'
                   or l.email like :q escape '\\')""")
    Page<Lead> search(LeadStatus status, String q, Pageable pageable);
}
