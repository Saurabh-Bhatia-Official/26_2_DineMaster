package com.dinemaster.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SettingsRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<StoredSetting> findTenantSettings(long tenantId) {
        return jdbc.query("SELECT setting_key, value_json, version, updated_at FROM settings "
                        + "WHERE tenant_id = ? AND branch_id IS NULL ORDER BY setting_key",
                (rs, row) -> read(rs.getString("setting_key"), rs.getString("value_json"),
                        rs.getInt("version"), "TENANT", rs.getObject("updated_at", OffsetDateTime.class)),
                tenantId);
    }

    public List<StoredSetting> findBranchSettings(long tenantId, long branchId) {
        return jdbc.query("SELECT setting_key, value_json, version, updated_at FROM settings "
                        + "WHERE tenant_id = ? AND branch_id = ? ORDER BY setting_key",
                (rs, row) -> read(rs.getString("setting_key"), rs.getString("value_json"),
                        rs.getInt("version"), "BRANCH", rs.getObject("updated_at", OffsetDateTime.class)),
                tenantId, branchId);
    }

    public Optional<String> findValueJson(long tenantId, Long branchId, String key) {
        String sql = branchId == null
                ? "SELECT value_json FROM settings WHERE tenant_id = ? AND branch_id IS NULL AND setting_key = ?"
                : "SELECT value_json FROM settings WHERE tenant_id = ? AND branch_id = ? AND setting_key = ?";
        List<String> rows = branchId == null
                ? jdbc.query(sql, (rs, row) -> rs.getString(1), tenantId, key)
                : jdbc.query(sql, (rs, row) -> rs.getString(1), tenantId, branchId, key);
        return rows.stream().findFirst();
    }

    public void save(long tenantId, Long branchId, String key, String valueJson, long userId) {
        String updateSql = branchId == null
                ? "UPDATE settings SET value_json = ?, version = version + 1, updated_by = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND branch_id IS NULL AND setting_key = ?"
                : "UPDATE settings SET value_json = ?, version = version + 1, updated_by = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND branch_id = ? AND setting_key = ?";
        int updated = branchId == null
                ? jdbc.update(updateSql, valueJson, userId, tenantId, key)
                : jdbc.update(updateSql, valueJson, userId, tenantId, branchId, key);
        if (updated == 0) {
            jdbc.update("INSERT INTO settings (tenant_id, branch_id, setting_key, value_json, version, updated_by) "
                            + "VALUES (?, ?, ?, ?, 1, ?)",
                    tenantId, branchId, key, valueJson, userId);
        }
    }

    public void audit(long tenantId, Long branchId, String key, String oldValueJson,
            String newValueJson, long userId) {
        jdbc.update("INSERT INTO setting_audit "
                        + "(tenant_id, branch_id, setting_key, old_value_json, new_value_json, changed_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                tenantId, branchId, key, oldValueJson, newValueJson, userId);
    }

    private StoredSetting read(String key, String valueJson, int version, String scope, OffsetDateTime updatedAt) {
        try {
            return new StoredSetting(key, objectMapper.readValue(valueJson, Object.class), version, scope, updatedAt);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored setting JSON is invalid", exception);
        }
    }

    public record StoredSetting(String key, Object value, int version, String scope, OffsetDateTime updatedAt) { }
}
