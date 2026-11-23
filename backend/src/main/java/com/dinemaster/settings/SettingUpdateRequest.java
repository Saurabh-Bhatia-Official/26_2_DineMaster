package com.dinemaster.settings;

import jakarta.validation.constraints.NotNull;

public record SettingUpdateRequest(@NotNull Object value) { }
