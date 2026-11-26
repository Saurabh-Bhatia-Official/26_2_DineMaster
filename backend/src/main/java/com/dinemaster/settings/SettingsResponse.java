package com.dinemaster.settings;

import java.util.Map;

public record SettingsResponse(Long tenantId, Long branchId, Map<String, SettingValueResponse> settings) { }
