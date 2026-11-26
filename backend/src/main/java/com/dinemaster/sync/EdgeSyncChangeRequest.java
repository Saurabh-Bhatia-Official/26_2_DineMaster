package com.dinemaster.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

public record EdgeSyncChangeRequest(@NotNull UUID changeId, @NotBlank @Size(max = 40) String entityType,
        @NotNull UUID entitySyncId, @NotBlank @Size(max = 12) String operation,
        @NotNull Map<String, Object> payload) { }
