package com.dinemaster.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads local environment configurations and normalizes PostgreSQL database
 * connection properties across cloud (Render, Supabase, Neon) and local environments.
 * 
 * Supports:
 * 1. Cloud PostgreSQL URIs (postgres:// or postgresql://) converted to standard JDBC format.
 * 2. Standard JDBC URLs (jdbc:postgresql://...).
 * 3. Individual parameters (DB_HOST, DB_PORT, DB_NAME, DB_USERNAME, DB_PASSWORD, DB_SSLMODE).
 * 4. Safe URL-decoding of special characters in credentials.
 * 5. Strict rejection of localhost database hosts when running in the 'prod' profile.
 */
public class LocalEnvironmentPostProcessor implements EnvironmentPostProcessor {

    public static final String DATABASE_PROPERTY_SOURCE_NAME = "dinemasterDatabaseEnv";
    public static final String LOCAL_PROPERTY_SOURCE_NAME = "dinemasterLocalEnv";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (hasProfile(environment, "test")) {
            return;
        }

        // 1. Load local gitignored .env files if present (for local dev)
        Map<String, Object> localEnvValues = new LinkedHashMap<>();
        loadFile(localEnvValues, Path.of(System.getProperty("user.home"), ".dinemaster", "local.env"));
        loadFile(localEnvValues, Path.of(".env"));
        loadFile(localEnvValues, Path.of("backend", ".env"));

        if (!localEnvValues.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(LOCAL_PROPERTY_SOURCE_NAME, localEnvValues));
        }

        // 2. Resolve & normalize database configuration
        Map<String, Object> databaseProperties = new LinkedHashMap<>();
        resolveDatabaseConfiguration(environment, localEnvValues, databaseProperties);

        if (!databaseProperties.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(DATABASE_PROPERTY_SOURCE_NAME, databaseProperties));
        }
    }

    private void resolveDatabaseConfiguration(ConfigurableEnvironment environment, Map<String, Object> fileValues, Map<String, Object> resolved) {
        boolean isProd = hasProfile(environment, "prod");

        // Discover database URL from environment or file values
        String rawDatabaseUrl = getProperty(environment, fileValues, "DATABASE_URL");
        if (rawDatabaseUrl == null || rawDatabaseUrl.isBlank()) {
            rawDatabaseUrl = getProperty(environment, fileValues, "DATABASE_INTERNAL_URL");
        }
        if (rawDatabaseUrl == null || rawDatabaseUrl.isBlank()) {
            rawDatabaseUrl = getProperty(environment, fileValues, "SPRING_DATASOURCE_URL");
        }

        String dbHost = getProperty(environment, fileValues, "DB_HOST");

        if (rawDatabaseUrl != null && !rawDatabaseUrl.isBlank()) {
            ParsedDatabaseUrl parsed = parseDatabaseUrl(rawDatabaseUrl.trim());

            if (isProd && (parsed.host == null || isLocalhost(parsed.host))) {
                throw new IllegalStateException(
                        "Production profile [prod] detected with localhost/empty database URL. " +
                        "Render production requires a remote PostgreSQL database. " +
                        "Please configure DATABASE_URL or DB_HOST in your Render Environment Variables.");
            }

            resolved.put("spring.datasource.url", parsed.jdbcUrl);
            resolved.put("SPRING_DATASOURCE_URL", parsed.jdbcUrl);
            resolved.put("spring.flyway.url", parsed.jdbcUrl);
            resolved.put("FLYWAY_URL", parsed.jdbcUrl);

            String finalUsername = (parsed.username != null && !parsed.username.isBlank())
                    ? parsed.username
                    : stripQuotes(getProperty(environment, fileValues, "DB_USERNAME"));
            if (finalUsername == null || finalUsername.isBlank()) {
                finalUsername = stripQuotes(getProperty(environment, fileValues, "SPRING_DATASOURCE_USERNAME"));
            }

            String finalPassword = (parsed.password != null)
                    ? parsed.password
                    : stripQuotes(getProperty(environment, fileValues, "DB_PASSWORD"));
            if (finalPassword == null) {
                finalPassword = stripQuotes(getProperty(environment, fileValues, "SPRING_DATASOURCE_PASSWORD"));
            }

            if (finalUsername != null && !finalUsername.isBlank()) {
                resolved.put("spring.datasource.username", finalUsername);
                resolved.put("SPRING_DATASOURCE_USERNAME", finalUsername);
                resolved.put("DB_USERNAME", finalUsername);
                resolved.put("spring.flyway.user", finalUsername);
                resolved.put("FLYWAY_USER", finalUsername);
            }
            if (finalPassword != null) {
                resolved.put("spring.datasource.password", finalPassword);
                resolved.put("SPRING_DATASOURCE_PASSWORD", finalPassword);
                resolved.put("DB_PASSWORD", finalPassword);
                resolved.put("spring.flyway.password", finalPassword);
                resolved.put("FLYWAY_PASSWORD", finalPassword);
            }
            resolved.put("spring.datasource.driver-class-name", "org.postgresql.Driver");

        } else if (dbHost != null && !dbHost.isBlank()) {
            if (isProd && isLocalhost(dbHost)) {
                throw new IllegalStateException(
                        "Production profile [prod] cannot use localhost as DB_HOST. " +
                        "Configure DB_HOST with your remote Render PostgreSQL instance hostname.");
            }

            String port = getPropertyOrDefault(environment, fileValues, "DB_PORT", "5432");
            String dbName = getPropertyOrDefault(environment, fileValues, "DB_NAME", "dine_master");
            String sslMode = getPropertyOrDefault(environment, fileValues, "DB_SSLMODE", isProd ? "require" : "prefer");
            String channelBinding = getProperty(environment, fileValues, "DB_CHANNEL_BINDING");
            if ((channelBinding == null || channelBinding.isBlank()) && dbHost.contains("neon.tech")) {
                channelBinding = "require";
            }
            String username = stripQuotes(getPropertyOrDefault(environment, fileValues, "DB_USERNAME", "dine_master_owner"));
            String password = stripQuotes(getPropertyOrDefault(environment, fileValues, "DB_PASSWORD", ""));

            StringBuilder queryParams = new StringBuilder("?sslmode=").append(sslMode);
            if (channelBinding != null && !channelBinding.isBlank()) {
                queryParams.append("&channel_binding=").append(channelBinding);
            }

            String jdbcUrl = "jdbc:postgresql://" + dbHost + ":" + port + "/" + dbName + queryParams.toString();
            resolved.put("spring.datasource.url", jdbcUrl);
            resolved.put("SPRING_DATASOURCE_URL", jdbcUrl);
            resolved.put("spring.flyway.url", jdbcUrl);
            resolved.put("FLYWAY_URL", jdbcUrl);
            resolved.put("spring.datasource.username", username);
            resolved.put("SPRING_DATASOURCE_USERNAME", username);
            resolved.put("DB_USERNAME", username);
            resolved.put("spring.flyway.user", username);
            resolved.put("FLYWAY_USER", username);
            resolved.put("spring.datasource.password", password);
            resolved.put("SPRING_DATASOURCE_PASSWORD", password);
            resolved.put("DB_PASSWORD", password);
            resolved.put("spring.flyway.password", password);
            resolved.put("FLYWAY_PASSWORD", password);
            resolved.put("spring.datasource.driver-class-name", "org.postgresql.Driver");

        } else if (isProd) {
            throw new IllegalStateException(
                    "Production profile [prod] requires a remote PostgreSQL database configuration, " +
                    "but neither DATABASE_URL nor DB_HOST was provided. " +
                    "Please configure DATABASE_URL or (DB_HOST, DB_NAME, DB_USERNAME, DB_PASSWORD) in your Render Environment Variables.");
        }
    }

    public static class ParsedDatabaseUrl {
        public final String jdbcUrl;
        public final String host;
        public final String username;
        public final String password;

        public ParsedDatabaseUrl(String jdbcUrl, String host, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.host = host;
            this.username = username;
            this.password = password;
        }
    }

    public static String stripQuotes(String str) {
        if (str == null) {
            return null;
        }
        String s = str.trim();
        while ((s.startsWith("'") && s.endsWith("'")) ||
               (s.startsWith("\"") && s.endsWith("\""))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    public static String cleanDatabaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim();
        // Remove 'psql ' prefix if user pasted a CLI command directly from database provider (e.g. Neon)
        if (cleaned.toLowerCase().startsWith("psql ")) {
            cleaned = cleaned.substring(5).trim();
        }
        return stripQuotes(cleaned);
    }

    public static ParsedDatabaseUrl parseDatabaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return new ParsedDatabaseUrl("", null, null, null);
        }

        String cleanedUrl = cleanDatabaseUrl(rawUrl);

        // 1. If already in JDBC format
        if (cleanedUrl.startsWith("jdbc:")) {
            String host = null;
            String username = null;
            String password = null;
            try {
                String sub = cleanedUrl.substring(5); // strip "jdbc:"
                URI uri = URI.create(sub);
                host = uri.getHost();
                String userInfo = uri.getUserInfo();
                if (userInfo != null && !userInfo.isEmpty()) {
                    int colonIdx = userInfo.indexOf(':');
                    if (colonIdx >= 0) {
                        username = stripQuotes(URLDecoder.decode(userInfo.substring(0, colonIdx), StandardCharsets.UTF_8));
                        password = stripQuotes(URLDecoder.decode(userInfo.substring(colonIdx + 1), StandardCharsets.UTF_8));
                    } else {
                        username = stripQuotes(URLDecoder.decode(userInfo, StandardCharsets.UTF_8));
                        password = "";
                    }
                }
                String query = uri.getQuery();
                if (query != null && !query.isEmpty()) {
                    for (String param : query.split("&")) {
                        int eq = param.indexOf('=');
                        if (eq > 0) {
                            String k = param.substring(0, eq).trim();
                            String v = stripQuotes(URLDecoder.decode(param.substring(eq + 1).trim(), StandardCharsets.UTF_8));
                            if ("user".equalsIgnoreCase(k) && (username == null || username.isBlank())) {
                                username = v;
                            } else if ("password".equalsIgnoreCase(k) && (password == null || password.isBlank())) {
                                password = v;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            return new ParsedDatabaseUrl(cleanedUrl, host, username, password);
        }

        // 2. Parse postgres:// or postgresql:// URI
        try {
            URI uri = URI.create(cleanedUrl);
            String scheme = uri.getScheme();
            if ("postgres".equalsIgnoreCase(scheme) || "postgresql".equalsIgnoreCase(scheme)) {
                String host = uri.getHost();
                int port = uri.getPort() == -1 ? 5432 : uri.getPort();
                String path = uri.getPath();
                String dbName = (path != null && path.startsWith("/")) ? path.substring(1) : (path != null ? path : "");

                String username = null;
                String password = null;
                String userInfo = uri.getUserInfo();
                if (userInfo != null && !userInfo.isEmpty()) {
                    int colonIdx = userInfo.indexOf(':');
                    if (colonIdx >= 0) {
                        username = stripQuotes(URLDecoder.decode(userInfo.substring(0, colonIdx), StandardCharsets.UTF_8));
                        password = stripQuotes(URLDecoder.decode(userInfo.substring(colonIdx + 1), StandardCharsets.UTF_8));
                    } else {
                        username = stripQuotes(URLDecoder.decode(userInfo, StandardCharsets.UTF_8));
                        password = "";
                    }
                }

                String query = uri.getQuery();
                String queryParams = "";
                if (query != null && !query.isEmpty()) {
                    if (!query.contains("sslmode=")) {
                        queryParams = "?" + query + "&sslmode=require";
                    } else {
                        queryParams = "?" + query;
                    }
                } else {
                    queryParams = "?sslmode=require";
                }

                String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + dbName + queryParams;
                return new ParsedDatabaseUrl(jdbcUrl, host, username, password);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid database URL format: " + rawUrl.replaceAll(":[^:@]+@", ":****@"), e);
        }

        return new ParsedDatabaseUrl(rawUrl, null, null, null);
    }

    public static boolean isLocalhost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase();
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("0.0.0.0");
    }

    private String getProperty(ConfigurableEnvironment env, Map<String, Object> fileValues, String key) {
        String val = System.getenv(key);
        if (val != null && !val.isBlank()) {
            return val.trim();
        }
        val = env.getProperty(key);
        if (val != null && !val.isBlank()) {
            return val.trim();
        }
        Object fileVal = fileValues.get(key);
        return fileVal != null ? fileVal.toString().trim() : null;
    }

    private String getPropertyOrDefault(ConfigurableEnvironment env, Map<String, Object> fileValues, String key, String defaultValue) {
        String val = getProperty(env, fileValues, key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private boolean hasProfile(ConfigurableEnvironment environment, String profile) {
        for (String active : environment.getActiveProfiles()) {
            if (profile.equalsIgnoreCase(active)) {
                return true;
            }
        }
        String configured = environment.getProperty("spring.profiles.active", "");
        return configured.toLowerCase().contains(profile.toLowerCase());
    }

    private void loadFile(Map<String, Object> values, Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("export ")) {
                    trimmed = trimmed.substring(7).trim();
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, separator).trim();
                String value = unquote(trimmed.substring(separator + 1).trim());
                if (!key.isEmpty() && !values.containsKey(key) && System.getenv(key) == null) {
                    values.put(key, value);
                }
            }
        } catch (IOException ignored) {
            // Missing or unreadable local files should not crash configuration discovery.
        }
    }

    private String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
