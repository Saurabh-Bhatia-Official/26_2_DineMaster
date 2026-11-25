package com.dinemaster.sync;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for reviewed Edge/cloud legacy-ID mappings. */
@Repository
public class SyncIdentityMapRepository {
    private final JdbcTemplate jdbc;

    public SyncIdentityMapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds a mapping only when it is new or exactly repeats an existing row.
     * This method intentionally does not decide whether two legacy rows match.
     */
    @Transactional
    public void recordReviewedMapping(String entityType, UUID syncId, Long edgeId,
            Long cloudId, String status, String resolutionNote) {
        if (entityType == null || entityType.isBlank() || syncId == null
                || (edgeId == null && cloudId == null)) {
            throw new IllegalArgumentException("A mapping requires entity, UUID, and at least one legacy ID");
        }
        if (!Objects.equals(status, "MATCHED") && !Objects.equals(status, "EDGE_ONLY")
                && !Objects.equals(status, "CLOUD_ONLY")) {
            throw new IllegalArgumentException("Mapping status must be an approved reconciliation result");
        }
        if ((Objects.equals(status, "MATCHED") && (edgeId == null || cloudId == null))
                || (Objects.equals(status, "EDGE_ONLY") && (edgeId == null || cloudId != null))
                || (Objects.equals(status, "CLOUD_ONLY") && (edgeId != null || cloudId == null))) {
            throw new IllegalArgumentException("Mapping status does not match the supplied legacy IDs");
        }

        var existing = jdbc.query(
                "SELECT edge_id, cloud_id, mapping_status, resolution_note "
                        + "FROM ops.sync_identity_map WHERE entity_type = ? AND sync_id = ?",
                (rs, row) -> new ExistingMapping(
                        (Long) rs.getObject("edge_id"),
                        (Long) rs.getObject("cloud_id"),
                        rs.getString("mapping_status"),
                        rs.getString("resolution_note")),
                entityType, syncId);
        if (!existing.isEmpty()) {
            ExistingMapping stored = existing.getFirst();
            if (Objects.equals(stored.edgeId(), edgeId) && Objects.equals(stored.cloudId(), cloudId)
                    && Objects.equals(stored.status(), status)
                    && Objects.equals(stored.note(), resolutionNote)) {
                return;
            }
            throw new IllegalStateException("UUID mapping already exists with a different resolution");
        }

        jdbc.update("""
                INSERT INTO ops.sync_identity_map
                  (entity_type, sync_id, edge_id, cloud_id, mapping_status, resolution_note)
                VALUES (?, ?, ?, ?, ?, ?)
                """, entityType, syncId, edgeId, cloudId, status, resolutionNote);
    }

    public OptionalLong findCloudId(String entityType, UUID syncId) {
        var ids = jdbc.query(
                "SELECT cloud_id FROM ops.sync_identity_map "
                        + "WHERE entity_type = ? AND sync_id = ? AND mapping_status = 'MATCHED'",
                (rs, row) -> (Long) rs.getObject("cloud_id"), entityType, syncId);
        return ids.isEmpty() || ids.getFirst() == null
                ? OptionalLong.empty()
                : OptionalLong.of(ids.getFirst());
    }

    private record ExistingMapping(Long edgeId, Long cloudId, String status, String note) { }
}
