package com.dinemaster.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class NeonLiveDatabaseTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_NEON_LIVE", matches = "true")
    void testDirectNeonConnection() {
        String rawUri = System.getenv("DATABASE_URL");
        assertNotNull(rawUri, "DATABASE_URL must be provided when TEST_NEON_LIVE=true");

        LocalEnvironmentPostProcessor.ParsedDatabaseUrl parsed = LocalEnvironmentPostProcessor.parseDatabaseUrl(rawUri);
        assertNotNull(parsed.jdbcUrl);
        assertNotNull(parsed.username);
        assertNotNull(parsed.password);

        assertDoesNotThrow(() -> {
            try (Connection conn = DriverManager.getConnection(parsed.jdbcUrl, parsed.username, parsed.password);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT version()")) {
                assertTrue(rs.next());
                assertNotNull(rs.getString(1));
            }
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_NEON_LIVE", matches = "true")
    void testFlywayConnectionToNeon() {
        String rawUri = System.getenv("DATABASE_URL");
        assertNotNull(rawUri, "DATABASE_URL must be provided when TEST_NEON_LIVE=true");

        LocalEnvironmentPostProcessor.ParsedDatabaseUrl parsed = LocalEnvironmentPostProcessor.parseDatabaseUrl(rawUri);

        assertDoesNotThrow(() -> {
            Flyway flyway = Flyway.configure()
                    .dataSource(parsed.jdbcUrl, parsed.username, parsed.password)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();

            MigrationInfoService info = flyway.info();
            MigrationInfo current = info.current();
            assertNotNull(current);
        });
    }
}
