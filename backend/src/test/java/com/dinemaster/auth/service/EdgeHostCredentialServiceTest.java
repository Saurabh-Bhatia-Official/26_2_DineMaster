package com.dinemaster.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

class EdgeHostCredentialServiceTest {
    private JdbcTemplate jdbc;
    private EmbeddedDatabase database;
    private EdgeHostCredentialService service;
    private KeyPair keys;
    private UUID hostId;

    @BeforeEach
    void setup() throws Exception {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create schema ops");
        jdbc.execute("create table ops.edge_hosts(id uuid primary key, tenant_id bigint, branch_id bigint, sync_epoch bigint, state varchar(16))");
        jdbc.execute("create table ops.edge_host_refresh_families(id uuid primary key, host_id uuid, expires_at timestamp with time zone, revoked_at timestamp with time zone)");
        jdbc.execute("create table ops.edge_host_refresh_tokens(token_hash char(64) primary key, family_id uuid, host_id uuid, expires_at timestamp with time zone, used_at timestamp with time zone, revoked_at timestamp with time zone)");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        service = new EdgeHostCredentialService(jdbc, pem("PRIVATE KEY", keys.getPrivate().getEncoded()),
                pem("PUBLIC KEY", keys.getPublic().getEncoded()));
        hostId = UUID.randomUUID();
        jdbc.update("insert into ops.edge_hosts values (?,?,?,?,'ACTIVE')", hostId, 23L, 51L, 4L);
    }

    @AfterEach
    void closeDatabase() { database.shutdown(); }

    @Test
    void issuesScopedRs256AccessAndRotatesRefreshWithReuseDetection() {
        var first = service.createInitial(hostId, 23, 51, 4);
        var scope = service.validateAccess(first.accessToken());
        assertThat(scope).isEqualTo(new EdgeHostCredentialService.HostScope(hostId, 23, 51, 4, "ACTIVE"));

        var rotated = service.rotate(first.refreshToken());
        assertThat(rotated).isNotNull();
        assertThat(service.validateAccess(rotated.accessToken())).isNotNull();
        assertThat(service.rotate(first.refreshToken())).isNull();
        assertThat(service.rotate(rotated.refreshToken())).isNull();
        List<String> storedHashes = jdbc.queryForList("select token_hash from ops.edge_host_refresh_tokens", String.class);
        assertThat(storedHashes).allMatch(hash -> !hash.trim().equals(first.refreshToken()));
    }

    @Test
    void rejectsExpiredWrongScopeWrongBranchAndStaleEpochTokens() {
        assertThat(service.validateAccess(token(hostId, 23, 51, 4, Instant.now().minusSeconds(1), "sync:push sync:pull"))).isNull();
        assertThat(service.validateAccess(token(hostId, 99, 51, 4, Instant.now().plusSeconds(60), "sync:push sync:pull"))).isNull();
        assertThat(service.validateAccess(token(hostId, 23, 52, 4, Instant.now().plusSeconds(60), "sync:push sync:pull"))).isNull();
        assertThat(service.validateAccess(token(hostId, 23, 51, 3, Instant.now().plusSeconds(60), "sync:push sync:pull"))).isNull();
        assertThat(service.validateAccess(token(hostId, 23, 51, 4, Instant.now().plusSeconds(60), "admin:*"))).isNull();
    }

    private String token(UUID host, long tenant, long branch, long epoch, Instant expires, String scope) {
        Instant now = Instant.now();
        return Jwts.builder().issuer("dinemaster-edge-host").audience().add("dinemaster-sync").and()
                .subject(host.toString()).claim("typ", "edge_host_sync").claim("hostId", host.toString())
                .claim("tenantId", tenant).claim("branchId", branch).claim("syncEpoch", epoch).claim("scope", scope)
                .issuedAt(Date.from(now)).expiration(Date.from(expires)).signWith(keys.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END " + label + "-----";
    }
}
