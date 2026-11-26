package com.dinemaster.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Separate RS256 credential family for the registered Edge host. */
@Service
public class EdgeHostCredentialService {
    private static final String ISSUER = "dinemaster-edge-host";
    private static final String AUDIENCE = "dinemaster-sync";
    private final JdbcTemplate jdbc;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    public EdgeHostCredentialService(JdbcTemplate jdbc,
            @Value("${dinemaster.edge-jwt-private-key-pem:}") String privatePem,
            @Value("${dinemaster.edge-jwt-public-key-pem:}") String publicPem) {
        this.jdbc = jdbc;
        if (privatePem == null || privatePem.isBlank() || publicPem == null || publicPem.isBlank()) {
            this.privateKey = null; this.publicKey = null;
        } else {
            this.privateKey = parsePrivate(privatePem); this.publicKey = parsePublic(publicPem);
        }
    }

    public boolean isReady() { return privateKey != null && publicKey != null; }

    @Transactional
    public Credentials createInitial(UUID hostId, long tenantId, long branchId, long epoch) {
        requireReady();
        UUID familyId = UUID.randomUUID();
        OffsetDateTime familyExpiry = OffsetDateTime.now().plus(Duration.ofDays(7));
        jdbc.update("insert into ops.edge_host_refresh_families(id,host_id,expires_at) values (?,?,?)", familyId, hostId, familyExpiry);
        String refresh = randomSecret();
        insertRefresh(familyId, hostId, refresh, familyExpiry);
        return new Credentials(access(hostId, tenantId, branchId, epoch), refresh,
                OffsetDateTime.now().plusMinutes(15), familyExpiry);
    }

    @Transactional
    public Credentials rotate(String rawRefreshToken) {
        requireReady();
        if (rawRefreshToken == null || rawRefreshToken.length() < 40 || rawRefreshToken.length() > 100) return null;
        String hash = sha256(rawRefreshToken);
        var tokens = jdbc.query("select t.family_id,t.host_id,t.expires_at,t.used_at,t.revoked_at,f.expires_at family_expires_at,f.revoked_at family_revoked_at "
                        + "from ops.edge_host_refresh_tokens t join ops.edge_host_refresh_families f on f.id=t.family_id "
                        + "where t.token_hash=? for update of t,f",
                (rs, n) -> new RefreshRow(rs.getObject("family_id", UUID.class), rs.getObject("host_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("used_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class), rs.getObject("family_expires_at", OffsetDateTime.class),
                        rs.getObject("family_revoked_at", OffsetDateTime.class)), hash);
        if (tokens.isEmpty()) return null;
        RefreshRow row = tokens.get(0); OffsetDateTime now = OffsetDateTime.now();
        if (row.usedAt != null) {
            jdbc.update("update ops.edge_host_refresh_families set revoked_at=COALESCE(revoked_at,?) where id=?", now, row.familyId);
            jdbc.update("update ops.edge_host_refresh_tokens set revoked_at=COALESCE(revoked_at,?) where family_id=?", now, row.familyId);
            return null;
        }
        if (row.revokedAt != null || row.familyRevokedAt != null || !row.expiresAt.isAfter(now)
                || !row.familyExpiresAt.isAfter(now)) return null;
        var hosts = jdbc.query("select tenant_id,branch_id,sync_epoch,state from ops.edge_hosts where id=?",
                (rs, n) -> new HostScope(rs.getLong("tenant_id"), rs.getLong("branch_id"), rs.getLong("sync_epoch"), rs.getString("state")), row.hostId);
        if (hosts.isEmpty() || !"ACTIVE".equals(hosts.get(0).state)) return null;
        jdbc.update("update ops.edge_host_refresh_tokens set used_at=? where token_hash=? and used_at is null", now, hash);
        String next = randomSecret(); insertRefresh(row.familyId, row.hostId, next, row.familyExpiresAt);
        HostScope host = hosts.get(0);
        return new Credentials(access(row.hostId, host.tenantId, host.branchId, host.epoch), next,
                now.plusMinutes(15), row.familyExpiresAt);
    }

    public HostScope validateAccess(String token) {
        requireReady();
        try {
            var signed = Jwts.parser().verifyWith(publicKey).requireIssuer(ISSUER).requireAudience(AUDIENCE)
                    .build().parseSignedClaims(token);
            if (!"RS256".equals(signed.getHeader().getAlgorithm())) return null;
            Claims claims = signed.getPayload();
            if (!"edge_host_sync".equals(claims.get("typ", String.class))) return null;
            UUID hostId = UUID.fromString(claims.get("hostId", String.class));
            if (!hostId.toString().equals(claims.getSubject())
                    || !"sync:push sync:pull".equals(claims.get("scope", String.class))) return null;
            long tenantId = claims.get("tenantId", Number.class).longValue();
            long branchId = claims.get("branchId", Number.class).longValue();
            long epoch = claims.get("syncEpoch", Number.class).longValue();
            var rows = jdbc.query("select tenant_id,branch_id,sync_epoch,state from ops.edge_hosts where id=?",
                    (rs, n) -> new HostScope(rs.getLong("tenant_id"), rs.getLong("branch_id"), rs.getLong("sync_epoch"), rs.getString("state")), hostId);
            if (rows.isEmpty()) return null;
            HostScope current = rows.get(0);
            return current.state.equals("ACTIVE") && current.tenantId == tenantId && current.branchId == branchId
                    && current.epoch == epoch ? new HostScope(hostId, tenantId, branchId, epoch, "ACTIVE") : null;
        } catch (Exception invalid) { return null; }
    }

    private String access(UUID hostId, long tenantId, long branchId, long epoch) {
        Date now = new Date();
        return Jwts.builder().issuer(ISSUER).audience().add(AUDIENCE).and().subject(hostId.toString())
                .claim("typ", "edge_host_sync").claim("hostId", hostId.toString())
                .claim("tenantId", tenantId).claim("branchId", branchId).claim("syncEpoch", epoch)
                .claim("scope", "sync:push sync:pull").id(UUID.randomUUID().toString())
                .issuedAt(now).expiration(new Date(now.getTime() + Duration.ofMinutes(15).toMillis()))
                .signWith(privateKey, Jwts.SIG.RS256).compact();
    }
    private void insertRefresh(UUID family, UUID host, String raw, OffsetDateTime expiry) {
        jdbc.update("insert into ops.edge_host_refresh_tokens(token_hash,family_id,host_id,expires_at) values (?,?,?,?)",
                sha256(raw), family, host, expiry);
    }
    private String randomSecret() { byte[] value = new byte[32]; random.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void requireReady() { if (!isReady()) throw new IllegalStateException("Edge host signing keys are not configured"); }
    private PrivateKey parsePrivate(String pem) {
        try { return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decode(pem))); }
        catch (Exception e) { throw new IllegalStateException("Invalid Edge RS256 private key", e); }
    }
    private PublicKey parsePublic(String pem) {
        try { return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(pem))); }
        catch (Exception e) { throw new IllegalStateException("Invalid Edge RS256 public key", e); }
    }
    private byte[] decode(String pem) { return Base64.getDecoder().decode(pem.replaceAll("-----BEGIN [^-]+-----", "").replaceAll("-----END [^-]+-----", "").replaceAll("\\s", "")); }
    private record RefreshRow(UUID familyId, UUID hostId, OffsetDateTime expiresAt, OffsetDateTime usedAt,
            OffsetDateTime revokedAt, OffsetDateTime familyExpiresAt, OffsetDateTime familyRevokedAt) { }
    public record HostScope(UUID hostId, long tenantId, long branchId, long epoch, String state) {
        public HostScope(long tenantId, long branchId, long epoch, String state) { this(null, tenantId, branchId, epoch, state); }
    }
    public record Credentials(String accessToken, String refreshToken, OffsetDateTime accessExpiresAt,
            OffsetDateTime refreshExpiresAt) { }
}
