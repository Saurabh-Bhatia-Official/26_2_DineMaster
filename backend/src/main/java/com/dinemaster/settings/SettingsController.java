package com.dinemaster.settings;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settings:view')")
    public SettingsResponse get(@RequestParam(required = false) Long branchId, Authentication authentication) {
        return settings.get(authentication, branchId);
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasAuthority('settings:update') and hasAuthority('settings:view')")
    public ResponseEntity<SettingsResponse> update(@PathVariable String key,
            @Valid @RequestBody SettingUpdateRequest request,
            @RequestParam(required = false) Long branchId,
            Authentication authentication) {
        return ResponseEntity.ok(settings.update(authentication, key, request.value(), branchId));
    }
}
