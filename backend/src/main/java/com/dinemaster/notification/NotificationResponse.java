package com.dinemaster.notification;

import java.time.OffsetDateTime;

public record NotificationResponse(
        Long id,
        Long branchId,
        String type,
        String title,
        String body,
        String refType,
        Long refId,
        OffsetDateTime readAt,
        OffsetDateTime createdAt) {
}
