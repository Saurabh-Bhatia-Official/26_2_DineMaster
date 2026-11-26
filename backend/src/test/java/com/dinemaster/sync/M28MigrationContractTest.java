package com.dinemaster.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class M28MigrationContractTest {
    @Test
    void phaseTwoMigrationIsAdditiveAndPreservesIntegerKeys() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V34__m28_identity_payment_foundation.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toUpperCase();
        }

        assertThat(sql).contains("ADD COLUMN SYNC_ID UUID");
        assertThat(sql).contains("OPS.SYNC_IDENTITY_MAP");
        assertThat(sql).contains("OPS.SYNC_DEVICE_STATE");
        assertThat(sql).contains("OPS.SYNC_CURSORS");
        assertThat(sql).contains("OPS.PROCESSED_CHANGES");
        assertThat(sql).contains("OPS.ASSIGN_SYNC_IDENTITY");
        assertThat(sql).contains("OPS.REGISTER_SYNC_IDENTITY");
        assertThat(sql).contains("'TRG_' || ENTITY_TABLE || '_ASSIGN_SYNC_IDENTITY'");
        assertThat(sql).contains("'TRG_' || ENTITY_TABLE || '_REGISTER_SYNC_IDENTITY'");
        assertThat(sql).contains("CREATE TABLE PAYMENT_EVENTS");
        assertThat(sql).contains("OPS.PAYMENT_REVERSAL_TOTALS");
        assertThat(sql).contains("TRG_TRACK_PAYMENT_REVERSAL_TOTAL");
        assertThat(sql).contains("TRG_PAYMENT_EVENTS_IMMUTABLE");
        assertThat(sql).contains("UNIQUE (TENANT_ID, IDEMPOTENCY_KEY)");
        assertThat(sql).contains("SYNC_ENABLED BOOLEAN NOT NULL DEFAULT FALSE");
        assertThat(sql).doesNotContain("DROP TABLE");
        assertThat(sql).doesNotContain("DROP COLUMN");
        assertThat(sql).doesNotContain("ALTER COLUMN ID TYPE");
    }

    @Test
    void mvpReceiptFingerprintMigrationIsAdditive() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V37__m28_sync_request_fingerprints.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toUpperCase();
        }
        assertThat(sql).contains("ALTER TABLE OPS.EDGE_PROCESSED_CHANGES ADD COLUMN REQUEST_HASH CHAR(64)");
        assertThat(sql).doesNotContain("DROP TABLE", "DROP COLUMN", "TRUNCATE");
    }
}
