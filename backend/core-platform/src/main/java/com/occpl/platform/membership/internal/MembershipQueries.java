package com.occpl.platform.membership.internal;

import com.occpl.platform.membership.api.MembershipStatus;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.paging.Likes;
import com.occpl.platform.shared.paging.PageRequests;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL for member listings and the last-admin rule.
 *
 * <p>Documented exception to the module rule "read other modules only through their API"
 * (ARCHITECTURE.md §4): searching members by email/name and counting active admins require joining
 * {@code platform_user}/{@code user_profile} (identity) and {@code membership_role}/{@code role}
 * (access). Going through the APIs would mean unbounded id fan-out. These queries never write those
 * tables and must be replaced by an API or read model if either module is extracted.
 */
@Repository
class MembershipQueries {

    private static final Map<String, String> SORTS = Map.of("createdAt", "m.created_at", "email", "u.email",
            "displayName", "p.display_name", "status", "m.status", "joinedAt", "m.joined_at");

    private final JdbcClient jdbc;

    MembershipQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record MemberRow(UUID membershipId, UUID userId, String email, String displayName, MembershipStatus status,
            Instant joinedAt, Instant createdAt) {
    }

    record MemberPage(List<MemberRow> rows, long total, int page, int size) {
    }

    MemberPage search(UUID companyId, MembershipStatus status, String roleCode, String q, Integer page, Integer size,
            List<String> sort) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? PageRequests.DEFAULT_SIZE : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > PageRequests.MAX_SIZE) {
            throw ApiException.validation("page must be >= 0 and size between 1 and " + PageRequests.MAX_SIZE + ".");
        }
        StringBuilder where = new StringBuilder(" where m.company_id = UUID_TO_BIN(:companyId)");
        Map<String, Object> params = new java.util.HashMap<>(Map.of("companyId", companyId.toString()));
        if (status != null) {
            where.append(" and m.status = :status");
            params.put("status", status.name());
        }
        if (roleCode != null && !roleCode.isBlank()) {
            where.append(" and exists (select 1 from membership_role mr join role r on r.id = mr.role_id"
                    + " where mr.membership_id = m.id and r.code = :roleCode)");
            params.put("roleCode", roleCode);
        }
        String like = Likes.prefix(q == null ? null : q.toLowerCase());
        String from = " from company_membership m join platform_user u on u.id = m.user_id"
                + " left join user_profile p on p.user_id = m.user_id";
        if (like != null && q.strip().length() >= 3) {
            // Start from the matching users (prefix indexes on email and display name) instead of walking
            // every member of a large company: ~4 ms instead of ~200 ms for 50,000 members (load test H-3).
            from = " from (select id user_id from platform_user where email like :q escape '\\\\'"
                    + " union select user_id from user_profile where display_name like :q escape '\\\\') hit"
                    + " join company_membership m on m.user_id = hit.user_id"
                    + " join platform_user u on u.id = m.user_id left join user_profile p on p.user_id = m.user_id";
            params.put("q", like);
        } else if (like != null) {
            // One or two characters match a large share of all users: filter the company's members instead.
            where.append(" and (u.email like :q escape '\\\\' or p.display_name like :q escape '\\\\')");
            params.put("q", like);
        }

        long total = jdbc.sql("select count(*)" + from + where).params(params).query(Long.class).single();
        String select = "select BIN_TO_UUID(m.id) membership_id, BIN_TO_UUID(m.user_id) user_id, u.email,"
                + " p.display_name, m.status, m.joined_at, m.created_at" + from + where + orderBy(sort)
                + " limit :limit offset :offset";
        params.put("limit", pageSize);
        params.put("offset", (long) pageNumber * pageSize);
        List<MemberRow> rows = jdbc.sql(select).params(params).query((rs, i) -> new MemberRow(
                UUID.fromString(rs.getString("membership_id")), UUID.fromString(rs.getString("user_id")),
                rs.getString("email"), rs.getString("display_name"), MembershipStatus.valueOf(rs.getString("status")),
                instant(rs.getTimestamp("joined_at")), instant(rs.getTimestamp("created_at")))).list();
        return new MemberPage(rows, total, pageNumber, pageSize);
    }

    java.util.Optional<MemberRow> member(UUID companyId, UUID membershipId) {
        return jdbc.sql("""
                        select BIN_TO_UUID(m.id) membership_id, BIN_TO_UUID(m.user_id) user_id, u.email,
                               p.display_name, m.status, m.joined_at, m.created_at
                        from company_membership m join platform_user u on u.id = m.user_id
                        left join user_profile p on p.user_id = m.user_id
                        where m.company_id = UUID_TO_BIN(:companyId) and m.id = UUID_TO_BIN(:membershipId)""")
                .param("companyId", companyId.toString()).param("membershipId", membershipId.toString())
                .query((rs, i) -> new MemberRow(UUID.fromString(rs.getString("membership_id")),
                        UUID.fromString(rs.getString("user_id")), rs.getString("email"), rs.getString("display_name"),
                        MembershipStatus.valueOf(rs.getString("status")), instant(rs.getTimestamp("joined_at")),
                        instant(rs.getTimestamp("created_at"))))
                .optional();
    }

    /** Members occupying a seat (ACTIVE or INVITED); FOR UPDATE serialises concurrent invitations. */
    long lockSeatsInUse(UUID companyId) {
        return jdbc.sql("""
                        select count(*) from company_membership
                        where company_id = UUID_TO_BIN(:companyId) and status in ('ACTIVE', 'INVITED')
                        for update""")
                .param("companyId", companyId.toString()).query(Long.class).single();
    }

    /**
     * ACTIVE memberships holding the system COMPANY_ADMIN role, locked FOR UPDATE so concurrent
     * removals of two admins cannot both succeed.
     */
    List<UUID> lockActiveAdmins(UUID companyId) {
        return jdbc.sql("""
                        select BIN_TO_UUID(m.id) from company_membership m
                        join membership_role mr on mr.membership_id = m.id
                        join role r on r.id = mr.role_id and r.code = 'COMPANY_ADMIN' and r.company_id is null
                        where m.company_id = UUID_TO_BIN(:companyId) and m.status = 'ACTIVE'
                        for update""")
                .param("companyId", companyId.toString())
                .query(String.class).list().stream().map(UUID::fromString).toList();
    }

    private static String orderBy(List<String> sort) {
        List<String> parts = new ArrayList<>();
        if (sort == null || sort.isEmpty()) {
            parts.add("m.created_at desc");
        } else {
            for (String s : sort) {
                String[] kv = s.split(",", -1);
                String column = SORTS.get(kv[0].strip());
                if (column == null || kv.length > 2) {
                    throw ApiException.validation("Sorting by '" + kv[0].strip() + "' is not supported.");
                }
                String direction = kv.length == 2 ? kv[1].strip().toLowerCase() : "asc";
                if (!direction.equals("asc") && !direction.equals("desc")) {
                    throw ApiException.validation("Sort direction must be asc or desc.");
                }
                parts.add(column + " " + direction);
            }
        }
        parts.add("m.id asc");
        return " order by " + String.join(", ", parts);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
