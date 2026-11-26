package com.dinemaster.restaurant.dto;

import java.time.OffsetDateTime;

public record BranchResponse(Long id, String name, String address, String phone, Long tenantId,
        String code, String timezone, boolean active, int version, OffsetDateTime deletedAt,
        OffsetDateTime createdAt) { }
