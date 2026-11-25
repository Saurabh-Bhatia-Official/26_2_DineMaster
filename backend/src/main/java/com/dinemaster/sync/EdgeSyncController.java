package com.dinemaster.sync;

import com.dinemaster.auth.service.EdgeHostCredentialService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sync")
public class EdgeSyncController {
    private final EdgeHostCredentialService credentials;
    private final EdgeSyncService sync;
    private final boolean enabled;

    public EdgeSyncController(EdgeHostCredentialService credentials, EdgeSyncService sync,
            @Value("${dinemaster.sync-enabled:false}") boolean enabled) {
        this.credentials = credentials; this.sync = sync; this.enabled = enabled;
    }

    @PostMapping("/push")
    public ResponseEntity<?> push(@RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody EdgeSyncPushRequest request) {
        var host = authorized(authorization);
        if (host == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "EDGE_HOST_TOKEN_INVALID"));
        return ResponseEntity.ok(sync.push(host, request.changes()));
    }

    @GetMapping("/pull")
    public ResponseEntity<?> pull(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "menu") String stream, @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "100") int limit) {
        var host = authorized(authorization);
        if (host == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "EDGE_HOST_TOKEN_INVALID"));
        if (!"menu".equals(stream) || limit < 1 || limit > 500)
            return ResponseEntity.badRequest().body(Map.of("code", "SYNC_REQUEST_INVALID"));
        return ResponseEntity.ok(sync.pullMenu(host, cursor, limit));
    }

    @PostMapping("/pull/ack")
    public ResponseEntity<?> acknowledgePull(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, String> body) {
        var host = authorized(authorization);
        if (host == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "EDGE_HOST_TOKEN_INVALID"));
        try {
            sync.acknowledgeCursor(host, body.get("cursor"));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("code", "SYNC_CURSOR_INVALID"));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var host = authorized(authorization);
        if (host == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "EDGE_HOST_TOKEN_INVALID"));
        return ResponseEntity.ok(sync.status(host));
    }

    private EdgeHostCredentialService.HostScope authorized(String authorization) {
        if (!enabled || authorization == null || !authorization.startsWith("Bearer ")) return null;
        try { return credentials.validateAccess(authorization.substring(7)); }
        catch (IllegalStateException unavailable) { return null; }
    }
}
