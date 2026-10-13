package com.occpl.sample;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TenantRepository {

    private final JdbcClient jdbc;

    TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Tenant(UUID companyId, String companyCode, String companyName, String status) {
        boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** Creates or re-activates the tenant; returns true if it already existed and was active. */
    boolean upsert(UUID companyId, String code, String name, String jobId) {
        boolean wasActive = find(companyId).map(Tenant::active).orElse(false);
        jdbc.sql("""
                insert into tenant (company_id, company_code, company_name, status, last_job_id, provisioned_at)
                values (:id, :code, :name, 'ACTIVE', :job, :now)
                on duplicate key update company_code = values(company_code), company_name = values(company_name),
                    status = 'ACTIVE', last_job_id = values(last_job_id), removed_at = null,
                    provisioned_at = if(status = 'ACTIVE', provisioned_at, values(provisioned_at))""")
                .param("id", companyId.toString()).param("code", code).param("name", name).param("job", jobId)
                .param("now", Timestamp.from(Instant.now())).update();
        return wasActive;
    }

    Optional<Tenant> find(UUID companyId) {
        return jdbc.sql("select company_id, company_code, company_name, status from tenant where company_id = :id")
                .param("id", companyId.toString())
                .query((rs, i) -> new Tenant(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                        rs.getString(4))).optional();
    }

    void markRemoved(UUID companyId) {
        jdbc.sql("update tenant set status = 'REMOVED', removed_at = :now where company_id = :id")
                .param("id", companyId.toString()).param("now", Timestamp.from(Instant.now())).update();
    }
}
