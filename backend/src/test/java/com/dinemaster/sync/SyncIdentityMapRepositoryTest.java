package com.dinemaster.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SyncIdentityMapRepositoryTest {
    @Autowired private SyncIdentityMapRepository mappings;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearMappings() {
        jdbc.update("DELETE FROM ops.sync_identity_map WHERE entity_type = 'm28_test'");
    }

    @Test
    void recordsReviewedMappingAndLooksUpMatchedCloudId() {
        UUID syncId = UUID.randomUUID();

        mappings.recordReviewedMapping("m28_test", syncId, 11L, 27L, "MATCHED", "verified fixture");

        assertThat(mappings.findCloudId("m28_test", syncId)).hasValue(27L);
        mappings.recordReviewedMapping("m28_test", syncId, 11L, 27L, "MATCHED", "verified fixture");
    }

    @Test
    void rejectsUnreviewedOrContradictoryMappings() {
        UUID syncId = UUID.randomUUID();
        assertThatThrownBy(() -> mappings.recordReviewedMapping(
                "m28_test", syncId, 11L, null, "MATCHED", null))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);

        mappings.recordReviewedMapping("m28_test", syncId, 11L, 27L, "MATCHED", "reviewed");
        assertThatThrownBy(() -> mappings.recordReviewedMapping(
                "m28_test", syncId, 12L, 27L, "MATCHED", "different rows"))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }
}
