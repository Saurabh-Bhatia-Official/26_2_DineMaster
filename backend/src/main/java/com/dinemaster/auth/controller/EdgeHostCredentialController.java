package com.dinemaster.auth.controller;

import com.dinemaster.auth.dto.EdgeHostRefreshRequest;
import com.dinemaster.auth.service.EdgeHostCredentialService;
import com.dinemaster.auth.service.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/edge-host-credentials")
public class EdgeHostCredentialController {
    private final EdgeHostCredentialService credentials;
    private final LoginRateLimiter limiter;
    private final boolean enabled;

    public EdgeHostCredentialController(EdgeHostCredentialService credentials, LoginRateLimiter limiter,
            @Value("${dinemaster.edge-control-plane-enabled:false}") boolean enabled) {
        this.credentials = credentials; this.limiter = limiter; this.enabled = enabled;
    }

    @PostMapping("/rotate")
    public ResponseEntity<?> rotate(@Valid @RequestBody EdgeHostRefreshRequest body, HttpServletRequest request) {
        if (!enabled) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "EDGE_CONTROL_PLANE_DISABLED"));
        if (!limiter.allow("edge-refresh:" + request.getRemoteAddr()))
            return ResponseEntity.status(429).body(Map.of("code", "RATE_LIMITED"));
        if (!credentials.isReady()) return ResponseEntity.status(503).body(Map.of("code", "EDGE_SIGNING_KEYS_UNAVAILABLE"));
        var issued = credentials.rotate(body.refreshToken());
        if (issued == null) return ResponseEntity.status(401).body(Map.of("code", "EDGE_REFRESH_INVALID"));
        return ResponseEntity.ok(Map.of("accessToken", issued.accessToken(), "refreshToken", issued.refreshToken(),
                "accessExpiresAt", issued.accessExpiresAt(), "refreshExpiresAt", issued.refreshExpiresAt()));
    }
}
