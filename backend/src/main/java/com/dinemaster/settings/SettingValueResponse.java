package com.dinemaster.settings;

import java.time.OffsetDateTime;

public record SettingValueResponse(Object value, int version, String scope, OffsetDateTime updatedAt) { }
