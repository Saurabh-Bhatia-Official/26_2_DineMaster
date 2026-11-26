package com.dinemaster.orders.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.List;

@Service
public class OrderAuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public OrderAuditService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public void transition(Long orderId, String entityType, Long entityId, String from, String to,
            Long actorId, Long approverId, String reason, String deviceId) {
        jdbc.update("""
                INSERT INTO order_status_logs(order_id, entity_type, entity_id, from_status, to_status,
                    actor_id, approver_id, reason, device_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, orderId, entityType, entityId, from, to, actorId, approverId, reason, deviceId);
    }

    public void event(Long orderId, String eventType, Map<String, ?> payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            jdbc.update("INSERT INTO order_events_outbox(order_id, event_type, payload) VALUES (?, ?, ?::jsonb)",
                    orderId, eventType, json);
        } catch (Exception ex) { throw new IllegalStateException("Could not persist order event", ex); }
    }

    public List<Map<String, Object>> timeline(Long orderId) {
        return jdbc.queryForList("""
                SELECT id, entity_type, entity_id, from_status, to_status, actor_id, approver_id,
                       reason, device_id, created_at
                FROM order_status_logs WHERE order_id = ? ORDER BY created_at, id
                """, orderId);
    }
}
