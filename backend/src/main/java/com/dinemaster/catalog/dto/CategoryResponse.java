package com.dinemaster.catalog.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CategoryResponse(Long id, String name, Long tenantId, Integer sortOrder,
        boolean active, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime deletedAt) {

    public record ListResponse(List<CategoryResponse> categories) { }
}
