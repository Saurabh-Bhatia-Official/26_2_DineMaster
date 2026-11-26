package com.dinemaster.kitchen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KitchenEventService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ApplicationEventPublisher publisher;

    public KitchenEventService(JdbcTemplate jdbc, ObjectMapper json, ApplicationEventPublisher publisher) {
        this.jdbc = jdbc; this.json = json; this.publisher = publisher;
    }

    @Transactional
    public long publishAfterCommit(Long branchId, String station, String type, Map<String, ?> data) {
        jdbc.update("INSERT INTO kitchen_event_counters(branch_id, last_seq) VALUES (?, 0) ON CONFLICT (branch_id) DO NOTHING", branchId);
        Long seq = jdbc.queryForObject("UPDATE kitchen_event_counters SET last_seq = last_seq + 1 WHERE branch_id = ? RETURNING last_seq", Long.class, branchId);
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("event", type); envelope.put("seq", seq); envelope.put("branchId", branchId);
            if (station != null) envelope.put("station", station);
            envelope.putAll(data);
            String payload = json.writeValueAsString(envelope);
            jdbc.update("INSERT INTO kitchen_events(branch_id, sequence, station_key, event_type, payload) VALUES (?, ?, ?, ?, ?::jsonb)",
                    branchId, seq, station, type, payload);
            publisher.publishEvent(new KitchenEventCommitted(branchId, station, type, seq, envelope));
            return seq;
        } catch (Exception ex) { throw new IllegalStateException("Could not persist kitchen event", ex); }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> replay(Long branchId, Long afterSeq, String station, int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        List<Map<String, Object>> rows = station == null || station.isBlank()
                ? jdbc.queryForList("SELECT sequence AS seq, event_type AS event, payload::text AS payload, created_at FROM kitchen_events WHERE branch_id = ? AND sequence > ? ORDER BY sequence LIMIT ?", branchId, afterSeq, bounded)
                : jdbc.queryForList("SELECT sequence AS seq, event_type AS event, payload::text AS payload, created_at FROM kitchen_events WHERE branch_id = ? AND sequence > ? AND (station_key = ? OR station_key IS NULL) ORDER BY sequence LIMIT ?", branchId, afterSeq, station, bounded);
        return rows.stream().map(row -> {
            Object raw = row.get("payload");
            if (raw instanceof String value) {
                try { row.put("payload", json.readValue(value, Map.class)); }
                catch (Exception ex) { throw new IllegalStateException("Stored kitchen event JSON is invalid", ex); }
            }
            return row;
        }).toList();
    }

    public record KitchenEventCommitted(Long branchId, String station, String type, Long sequence, Map<String, Object> payload) { }
}
