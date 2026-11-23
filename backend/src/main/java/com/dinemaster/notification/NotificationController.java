package com.dinemaster.notification;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('notification:view')")
    public ResponseEntity<?> inbox(
            @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication) {
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "limit must be between 1 and 100"));
        }
        return ResponseEntity.ok(notifications.inbox(authentication, unread, limit));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("hasAuthority('notification:view')")
    public ResponseEntity<Void> markRead(@PathVariable long id, Authentication authentication) {
        return notifications.markRead(authentication, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/read-all")
    @PreAuthorize("hasAuthority('notification:view')")
    public Map<String, Integer> markAllRead(Authentication authentication) {
        return Map.of("updatedCount", notifications.markAllRead(authentication));
    }
}
