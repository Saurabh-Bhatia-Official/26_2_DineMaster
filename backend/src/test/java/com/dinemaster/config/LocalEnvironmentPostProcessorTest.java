package com.dinemaster.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class LocalEnvironmentPostProcessorTest {

    @Test
    void parseDatabaseUrlConvertsPostgresUriToJdbc() {
        String raw = "postgres://dineuser:secretpass@dpg-c0123456789-a.oregon-postgres.render.com:5432/dinemaster_prod";
        LocalEnvironmentPostProcessor.ParsedDatabaseUrl parsed = LocalEnvironmentPostProcessor.parseDatabaseUrl(raw);

        assertEquals("jdbc:postgresql://dpg-c0123456789-a.oregon-postgres.render.com:5432/dinemaster_prod?sslmode=require", parsed.jdbcUrl);
        assertEquals("dpg-c0123456789-a.oregon-postgres.render.com", parsed.host);
        assertEquals("dineuser", parsed.username);
        assertEquals("secretpass", parsed.password);
    }

    @Test
    void parseDatabaseUrlDecodesSpecialCharactersInCredentials() {
        String raw = "postgresql://user%40domain.com:p%40ss%23word%21@dpg-c0123456789-a.oregon-postgres.render.com/dinemaster";
        LocalEnvironmentPostProcessor.ParsedDatabaseUrl parsed = LocalEnvironmentPostProcessor.parseDatabaseUrl(raw);

        assertEquals("jdbc:postgresql://dpg-c0123456789-a.oregon-postgres.render.com:5432/dinemaster?sslmode=require", parsed.jdbcUrl);
        assertEquals("dpg-c0123456789-a.oregon-postgres.render.com", parsed.host);
        assertEquals("user@domain.com", parsed.username);
        assertEquals("p@ss#word!", parsed.password);
    }

    @Test
    void parseDatabaseUrlPreservesJdbcUrl() {
        String raw = "jdbc:postgresql://custom-host:5432/custom_db?sslmode=verify-full";
        LocalEnvironmentPostProcessor.ParsedDatabaseUrl parsed = LocalEnvironmentPostProcessor.parseDatabaseUrl(raw);

        assertEquals(raw, parsed.jdbcUrl);
        assertEquals("custom-host", parsed.host);
    }

    @Test
    void isLocalhostCorrectlyIdentifiesLocalAddresses() {
        assertTrue(LocalEnvironmentPostProcessor.isLocalhost("localhost"));
        assertTrue(LocalEnvironmentPostProcessor.isLocalhost("127.0.0.1"));
        assertTrue(LocalEnvironmentPostProcessor.isLocalhost("::1"));
        assertTrue(LocalEnvironmentPostProcessor.isLocalhost("0.0.0.0"));
        assertFalse(LocalEnvironmentPostProcessor.isLocalhost("dpg-c0123456789-a.oregon-postgres.render.com"));
        assertFalse(LocalEnvironmentPostProcessor.isLocalhost("postgres.mycompany.internal"));
    }

    @Test
    void postProcessRejectsLocalhostInProdProfile() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod");
        System.setProperty("DB_HOST", "localhost");

        LocalEnvironmentPostProcessor processor = new LocalEnvironmentPostProcessor();
        try {
            assertThrows(IllegalStateException.class, () -> processor.postProcessEnvironment(env, new SpringApplication()));
        } finally {
            System.clearProperty("DB_HOST");
        }
    }

    @Test
    void postProcessConfiguresRemotePostgresInProd() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod");
        System.setProperty("DATABASE_URL", "postgres://testuser:testpass@dpg-test-a.oregon-postgres.render.com:5432/testdb");

        LocalEnvironmentPostProcessor processor = new LocalEnvironmentPostProcessor();
        try {
            processor.postProcessEnvironment(env, new SpringApplication());
            assertEquals("jdbc:postgresql://dpg-test-a.oregon-postgres.render.com:5432/testdb?sslmode=require",
                    env.getProperty("spring.datasource.url"));
            assertEquals("testuser", env.getProperty("spring.datasource.username"));
            assertEquals("testpass", env.getProperty("spring.datasource.password"));
        } finally {
            System.clearProperty("DATABASE_URL");
        }
    }
}
