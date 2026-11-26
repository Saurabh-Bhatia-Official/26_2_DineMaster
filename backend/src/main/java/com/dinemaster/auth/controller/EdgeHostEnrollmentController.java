package com.dinemaster.auth.controller;

import com.dinemaster.auth.dto.ActivationCodeCreateRequest;
import com.dinemaster.auth.dto.EdgeHostActivationRequest;
import com.dinemaster.auth.service.EdgeHostProof;
import com.dinemaster.auth.service.EdgeHostCredentialService;
import com.dinemaster.auth.service.LoginRateLimiter;
import com.dinemaster.restaurant.repository.RestaurantRepository;
import com.dinemaster.tenant.TenantRepository;
import com.dinemaster.user.entity.User;
import com.dinemaster.user.repository.UserBranchRepository;
import com.dinemaster.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Development-only control plane for Edge host identity and credentials. */
@RestController
@RequestMapping("/api/v1/auth/edge-host-activations")
public class EdgeHostEnrollmentController {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final RestaurantRepository branches;
    private final TenantRepository tenants;
    private final UserBranchRepository userBranches;
    private final LoginRateLimiter rateLimiter;
    private final EdgeHostCredentialService credentials;
    private final SecureRandom random = new SecureRandom();
    private final boolean enabled;

    public EdgeHostEnrollmentController(JdbcTemplate jdbc, UserRepository users, RestaurantRepository branches,
            TenantRepository tenants, UserBranchRepository userBranches,
            LoginRateLimiter rateLimiter, EdgeHostCredentialService credentials,
            @Value("${dinemaster.edge-control-plane-enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.users = users; this.branches = branches; this.tenants = tenants;
        this.userBranches = userBranches; this.rateLimiter = rateLimiter; this.credentials = credentials;
        this.enabled = enabled;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('edge_host:activate')")
    public ResponseEntity<?> create(Authentication authentication, @Valid @RequestBody ActivationCodeCreateRequest body) {
        if (!enabled) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "EDGE_CONTROL_PLANE_DISABLED"));
        User actor = authenticatedUser(authentication);
        var branch = branches.findById(body.branchId()).orElse(null);
        if (actor == null || branch == null || branch.getDeletedAt() != null
                || !java.util.Objects.equals(actor.getTenantId(), branch.getTenantId())
                || !userBranches.isAssigned(actor.getId(), branch.getId())
                || tenants.findById(branch.getTenantId()).filter(com.dinemaster.tenant.Tenant::isActive).isEmpty())
            return ResponseEntity.notFound().build();
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id = UUID.randomUUID(); OffsetDateTime expires = OffsetDateTime.now().plusMinutes(15);
        jdbc.update("insert into ops.edge_host_activations(id,tenant_id,branch_id,activation_code_hash,expires_at,created_by) values (?,?,?,?,?,?)",
                id, actor.getTenantId(), branch.getId(), sha256(code), expires, actor.getId());
        String branchSyncId = jdbc.queryForObject("select sync_id::text from restaurants where id=?", String.class, branch.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("activationId", id, "activationCode", code,
                "branchSyncId", branchSyncId, "expiresAt", expires));
    }

    @PostMapping("/consume")
    @Transactional
    public ResponseEntity<?> consume(@Valid @RequestBody EdgeHostActivationRequest body, HttpServletRequest request) {
        if (!enabled) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "EDGE_CONTROL_PLANE_DISABLED"));
        if (!credentials.isReady()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "EDGE_SIGNING_KEYS_UNAVAILABLE"));
        if (!rateLimiter.allow("edge-host:" + request.getRemoteAddr()))
            return ResponseEntity.status(429).body(Map.of("code", "RATE_LIMITED"));
        var rows = jdbc.query("select id,tenant_id,branch_id,expires_at,consumed_at from ops.edge_host_activations where activation_code_hash=? for update",
                (rs, n) -> new Activation(rs.getObject("id", UUID.class), rs.getLong("tenant_id"), rs.getLong("branch_id"),
                        rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("consumed_at", OffsetDateTime.class)), sha256(body.activationCode()));
        if (rows.isEmpty()) return ResponseEntity.status(401).body(Map.of("code", "ACTIVATION_INVALID"));
        Activation activation = rows.get(0); OffsetDateTime now = OffsetDateTime.now();
        if (activation.consumedAt != null || !activation.expiresAt.isAfter(now))
            return ResponseEntity.status(401).body(Map.of("code", "ACTIVATION_INVALID"));
        final String fingerprint;
        try { fingerprint = EdgeHostProof.verifyAndFingerprint(activation.id.toString(), body.nonce(), body.publicKeyPem(), body.proofSignatureBase64()); }
        catch (IllegalArgumentException invalid) { return ResponseEntity.status(401).body(Map.of("code", "PROOF_INVALID")); }
        UUID hostId = UUID.randomUUID();
        var inserted = jdbc.query("insert into ops.edge_hosts(id,tenant_id,branch_id,public_key_pem,public_key_sha256,state,sync_epoch) "
                        + "values (?,?,?,?,?,'ACTIVE',1) on conflict (tenant_id,branch_id) where state='ACTIVE' do nothing returning id",
                (rs, row) -> rs.getObject(1, UUID.class), hostId, activation.tenantId, activation.branchId,
                body.publicKeyPem(), fingerprint);
        if (inserted.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "ACTIVE_EDGE_HOST_EXISTS"));
        }
        jdbc.update("update ops.edge_host_activations set consumed_at=? where id=? and consumed_at is null", now, activation.id);
        var issued = credentials.createInitial(hostId, activation.tenantId, activation.branchId, 1);
        String branchSyncId = jdbc.queryForObject("select sync_id::text from restaurants where id=?", String.class, activation.branchId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("hostId", hostId, "tenantId", activation.tenantId,
                "branchId", activation.branchId, "branchSyncId", branchSyncId, "syncEpoch", 1, "credentialIssued", true,
                "accessToken", issued.accessToken(), "refreshToken", issued.refreshToken(),
                "accessExpiresAt", issued.accessExpiresAt(), "refreshExpiresAt", issued.refreshExpiresAt()));
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        return users.findByUsername(authentication.getName()).orElse(null);
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private record Activation(UUID id, long tenantId, long branchId, OffsetDateTime expiresAt, OffsetDateTime consumedAt) { }
}
