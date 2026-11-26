package com.dinemaster.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {
    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<NotificationResponse> findInbox(long tenantId, long userId, String roleCode,
            boolean owner, List<Long> branchIds, boolean unreadOnly, int limit) {
        var args = new ArrayList<Object>();
        String sql = "SELECT id, branch_id, type, title, body, ref_type, ref_id, read_at, created_at "
                + "FROM notifications WHERE tenant_id = ? AND " + recipientClause()
                + " AND (branch_id IS NULL OR ? OR branch_id IN (" + placeholders(branchIds.size()) + "))";
        args.add(tenantId);
        args.add(userId);
        args.add(roleCode);
        args.add(owner);
        args.addAll(branchIds);
        if (unreadOnly) sql += " AND read_at IS NULL";
        sql += " ORDER BY created_at DESC, id DESC LIMIT ?";
        args.add(limit);
        return jdbc.query(sql, ROW_MAPPER, args.toArray());
    }

    public int markRead(long id, long tenantId, long userId, String roleCode, boolean owner,
            List<Long> branchIds) {
        var args = new ArrayList<Object>();
        String sql = "UPDATE notifications SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP) "
                + "WHERE id = ? AND tenant_id = ? AND " + recipientClause()
                + " AND (branch_id IS NULL OR ? OR branch_id IN (" + placeholders(branchIds.size()) + "))";
        args.add(id);
        args.add(tenantId);
        args.add(userId);
        args.add(roleCode);
        args.add(owner);
        args.addAll(branchIds);
        return jdbc.update(sql, args.toArray());
    }

    public int markAllRead(long tenantId, long userId, String roleCode, boolean owner,
            List<Long> branchIds) {
        var args = new ArrayList<Object>();
        String sql = "UPDATE notifications SET read_at = CURRENT_TIMESTAMP "
                + "WHERE tenant_id = ? AND " + recipientClause()
                + " AND (branch_id IS NULL OR ? OR branch_id IN (" + placeholders(branchIds.size()) + "))"
                + " AND read_at IS NULL";
        args.add(tenantId);
        args.add(userId);
        args.add(roleCode);
        args.add(owner);
        args.addAll(branchIds);
        return jdbc.update(sql, args.toArray());
    }

    private static String recipientClause() {
        return "(user_id = ? OR (user_id IS NULL AND role_code = ?))";
    }

    private static String placeholders(int count) {
        if (count == 0) return "NULL";
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static final RowMapper<NotificationResponse> ROW_MAPPER =
            (rs, rowNum) -> new NotificationResponse(
                    rs.getLong("id"), nullableLong(rs, "branch_id"), rs.getString("type"),
                    rs.getString("title"), rs.getString("body"), rs.getString("ref_type"),
                    nullableLong(rs, "ref_id"), rs.getObject("read_at", java.time.OffsetDateTime.class),
                    rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
