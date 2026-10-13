package com.occpl.platform.audit.internal;

import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.paging.Likes;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Newest-first keyset queries over the audit log (bounded pages, stable under concurrent inserts). */
@Service
public class AuditQueryService {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    AuditQueryService(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public record Filter(UUID companyId, UUID actorUserId, String actionPrefix, String targetType, String targetId,
            Instant from, Instant to) {
    }

    public record Entry(UUID id, Instant occurredAt, String actorType, UUID actorUserId, UUID companyId, String action,
            String targetType, String targetId, String outcome, String correlationId, JsonNode details) {
    }

    public record EntryPage(List<Entry> items, String nextCursor) {
    }

    public EntryPage search(Filter filter, String cursor, int size) {
        if (size < 1 || size > 100) {
            throw ApiException.validation("size must be between 1 and 100.");
        }
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (filter.companyId() != null) {
            where.append(" and company_id = UUID_TO_BIN(:companyId)");
            params.put("companyId", filter.companyId().toString());
        }
        if (filter.actorUserId() != null) {
            where.append(" and actor_user_id = UUID_TO_BIN(:actor)");
            params.put("actor", filter.actorUserId().toString());
        }
        String action = Likes.prefix(filter.actionPrefix());
        if (action != null) {
            where.append(" and action like :action escape '\\\\'");
            params.put("action", action);
        }
        if (filter.targetType() != null) {
            where.append(" and target_type = :targetType");
            params.put("targetType", filter.targetType());
        }
        if (filter.targetId() != null) {
            where.append(" and target_id = :targetId");
            params.put("targetId", filter.targetId());
        }
        if (filter.from() != null) {
            where.append(" and occurred_at >= :from");
            params.put("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" and occurred_at < :to");
            params.put("to", Timestamp.from(filter.to()));
        }
        if (cursor != null && !cursor.isBlank()) {
            Cursor c = Cursor.decode(cursor);
            where.append(" and (occurred_at < :cursorAt or (occurred_at = :cursorAt and id < UUID_TO_BIN(:cursorId)))");
            params.put("cursorAt", Timestamp.from(c.occurredAt()));
            params.put("cursorId", c.id().toString());
        }
        params.put("limit", size + 1);
        List<Entry> rows = jdbc.sql("""
                        select BIN_TO_UUID(id) id, occurred_at, actor_type, BIN_TO_UUID(actor_user_id) actor_user_id,
                               BIN_TO_UUID(company_id) company_id, action, target_type, target_id, outcome,
                               correlation_id, details
                        from audit_log""" + where + " order by occurred_at desc, id desc limit :limit")
                .params(params)
                .query((rs, i) -> new Entry(UUID.fromString(rs.getString("id")),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor_type"),
                        uuid(rs.getString("actor_user_id")), uuid(rs.getString("company_id")), rs.getString("action"),
                        rs.getString("target_type"), rs.getString("target_id"), rs.getString("outcome"),
                        rs.getString("correlation_id"),
                        rs.getString("details") == null ? null : jsonMapper.readTree(rs.getString("details"))))
                .list();
        boolean more = rows.size() > size;
        List<Entry> items = more ? rows.subList(0, size) : rows;
        String next = more ? new Cursor(items.getLast().occurredAt(), items.getLast().id()).encode() : null;
        return new EntryPage(items, next);
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private record Cursor(Instant occurredAt, UUID id) {

        String encode() {
            String raw = occurredAt.toString() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String value) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|");
                return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw ApiException.validation("Invalid cursor.");
            }
        }
    }
}
