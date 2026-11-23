package com.dinemaster.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dinemaster.role.Roles;
import com.dinemaster.security.BranchScopeService;
import com.dinemaster.user.entity.User;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
    private static final String GST_SCALE = "gst_scale";
    private static final String SHOW_WAITER = "show_waiter_assignment";
    private static final String SHOW_CHEF = "show_chef_assignment";

    private final SettingsRepository settings;
    private final BranchScopeService branchScope;
    private final ObjectMapper objectMapper;

    public SettingsService(SettingsRepository settings, BranchScopeService branchScope, ObjectMapper objectMapper) {
        this.settings = settings;
        this.branchScope = branchScope;
        this.objectMapper = objectMapper;
    }

    public SettingsResponse get(Authentication authentication, Long branchId) {
        User actor = requireTenantUser(authentication);
        if (branchId != null) branchScope.requireBranch(authentication, branchId);

        Map<String, SettingValueResponse> resolved = defaults();
        settings.findTenantSettings(actor.getTenantId()).forEach(setting -> resolved.put(setting.key(), toResponse(setting)));
        if (branchId != null) {
            settings.findBranchSettings(actor.getTenantId(), branchId)
                    .forEach(setting -> resolved.put(setting.key(), toResponse(setting)));
        }
        return new SettingsResponse(actor.getTenantId(), branchId, resolved);
    }

    @Transactional
    public SettingsResponse update(Authentication authentication, String key, Object rawValue, Long branchId) {
        User actor = requireTenantUser(authentication);
        if (!isSupported(key)) throw new IllegalArgumentException("Unsupported setting key");
        boolean owner = Roles.matches(actor.getRole(), "OWNER");
        if (branchId != null) branchScope.requireBranch(authentication, branchId);
        if (!owner && branchId == null) {
            throw new AccessDeniedException("Managers must specify an assigned branch");
        }
        if (!owner && !SHOW_WAITER.equals(key) && !SHOW_CHEF.equals(key)) {
            throw new AccessDeniedException("Managers can update operational settings for assigned branches only");
        }

        Object value = validate(key, rawValue);
        String newValueJson = toJson(value);
        String oldValueJson = settings.findValueJson(actor.getTenantId(), branchId, key).orElse(null);
        settings.save(actor.getTenantId(), branchId, key, newValueJson, actor.getId());
        settings.audit(actor.getTenantId(), branchId, key, oldValueJson, newValueJson, actor.getId());
        return get(authentication, branchId);
    }

    private User requireTenantUser(Authentication authentication) {
        User actor = branchScope.requireUser(authentication);
        if (actor.getTenantId() == null) throw new AccessDeniedException("Tenant staff account is required");
        return actor;
    }

    private static boolean isSupported(String key) {
        return GST_SCALE.equals(key) || SHOW_WAITER.equals(key) || SHOW_CHEF.equals(key);
    }

    private static Object validate(String key, Object value) {
        if (SHOW_WAITER.equals(key) || SHOW_CHEF.equals(key)) {
            if (value instanceof Boolean) return value;
            throw new IllegalArgumentException("Setting value must be a boolean");
        }
        if (!(value instanceof List<?> values) || values.isEmpty() || values.size() > 21) {
            throw new IllegalArgumentException("gst_scale must contain between 1 and 21 rates");
        }
        var rates = new TreeSet<Double>();
        for (Object item : values) {
            if (!(item instanceof Number number)) throw new IllegalArgumentException("GST rates must be numbers");
            double rate = number.doubleValue();
            if (!Double.isFinite(rate) || rate < 0 || rate > 100) {
                throw new IllegalArgumentException("GST rates must be between 0 and 100");
            }
            rates.add(rate);
        }
        if (rates.size() != values.size()) throw new IllegalArgumentException("GST rates must be unique");
        return new ArrayList<>(rates);
    }

    private Map<String, SettingValueResponse> defaults() {
        Map<String, SettingValueResponse> result = new LinkedHashMap<>();
        result.put(GST_SCALE, new SettingValueResponse(List.of(0.0, 5.0, 12.0, 18.0, 28.0), 0, "DEFAULT", null));
        result.put(SHOW_WAITER, new SettingValueResponse(true, 0, "DEFAULT", null));
        result.put(SHOW_CHEF, new SettingValueResponse(true, 0, "DEFAULT", null));
        return result;
    }

    private static SettingValueResponse toResponse(SettingsRepository.StoredSetting setting) {
        return new SettingValueResponse(setting.value(), setting.version(), setting.scope(), setting.updatedAt());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Setting value could not be encoded", exception);
        }
    }
}
