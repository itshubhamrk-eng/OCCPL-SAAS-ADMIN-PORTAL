package com.occpl.platform.entitlement.internal;

import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.id.UuidV7;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Atomic usage counting (DATABASE.md §4). The limit is enforced inside a single conditional UPDATE,
 * so concurrent requests can never push usage over the limit; the idempotency key is unique per
 * company, so a retried request is recorded at most once.
 */
@Repository
class UsageCounters {

    static final Instant STOCK_PERIOD = Instant.EPOCH;

    private final JdbcClient jdbc;
    private final Clock clock;

    UsageCounters(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    record Replay(long usedAfter, long quantity) {
    }

    Optional<Replay> replay(UUID companyId, String idempotencyKey) {
        return jdbc.sql("""
                        select used_after, quantity from usage_record
                        where company_id = UUID_TO_BIN(:companyId) and idempotency_key = :key""")
                .param("companyId", companyId.toString()).param("key", idempotencyKey)
                .query((rs, i) -> new Replay(rs.getLong("used_after"), rs.getLong("quantity"))).optional();
    }

    long used(UUID companyId, UUID featureId, Instant periodStart) {
        return jdbc.sql("""
                        select used from usage_counter
                        where company_id = UUID_TO_BIN(:companyId) and feature_id = UUID_TO_BIN(:featureId)
                          and period_start = :periodStart""")
                .param("companyId", companyId.toString()).param("featureId", featureId.toString())
                .param("periodStart", Timestamp.from(periodStart))
                .query(Long.class).optional().orElse(0L);
    }

    /** Must run in a transaction: a failed limit check or duplicate key rolls the whole change back. */
    long consume(UUID companyId, UUID featureId, Instant periodStart, long quantity, Long limit, String key) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("""
                        insert into usage_counter (company_id, feature_id, period_start, used, updated_at)
                        values (UUID_TO_BIN(:companyId), UUID_TO_BIN(:featureId), :periodStart, 0, :now)
                        on duplicate key update used = used""")
                .param("companyId", companyId.toString()).param("featureId", featureId.toString())
                .param("periodStart", Timestamp.from(periodStart)).param("now", now).update();
        int updated = jdbc.sql("""
                        update usage_counter set used = used + :quantity, updated_at = :now
                        where company_id = UUID_TO_BIN(:companyId) and feature_id = UUID_TO_BIN(:featureId)
                          and period_start = :periodStart and (:limit is null or used + :quantity <= :limit)""")
                .param("quantity", quantity).param("now", now).param("limit", limit)
                .param("companyId", companyId.toString()).param("featureId", featureId.toString())
                .param("periodStart", Timestamp.from(periodStart)).update();
        if (updated == 0) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "The usage limit for this feature has been reached.");
        }
        long used = used(companyId, featureId, periodStart);
        record(companyId, featureId, periodStart, quantity, key, used);
        return used;
    }

    long release(UUID companyId, UUID featureId, Instant periodStart, long quantity, String key) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("""
                        update usage_counter set used = greatest(used - :quantity, 0), updated_at = :now
                        where company_id = UUID_TO_BIN(:companyId) and feature_id = UUID_TO_BIN(:featureId)
                          and period_start = :periodStart""")
                .param("quantity", quantity).param("now", now)
                .param("companyId", companyId.toString()).param("featureId", featureId.toString())
                .param("periodStart", Timestamp.from(periodStart)).update();
        long used = used(companyId, featureId, periodStart);
        record(companyId, featureId, periodStart, -quantity, key, used);
        return used;
    }

    private void record(UUID companyId, UUID featureId, Instant periodStart, long quantity, String key, long usedAfter) {
        jdbc.sql("""
                        insert into usage_record (id, company_id, feature_id, quantity, idempotency_key, period_start,
                                                  used_after, recorded_at)
                        values (UUID_TO_BIN(:id), UUID_TO_BIN(:companyId), UUID_TO_BIN(:featureId), :quantity, :key,
                                :periodStart, :usedAfter, :now)""")
                .param("id", UuidV7.generate(clock).toString()).param("companyId", companyId.toString())
                .param("featureId", featureId.toString()).param("quantity", quantity).param("key", key)
                .param("periodStart", Timestamp.from(periodStart)).param("usedAfter", usedAfter)
                .param("now", Timestamp.from(clock.instant())).update();
    }
}
