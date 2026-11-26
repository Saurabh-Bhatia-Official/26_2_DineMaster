package com.dinemaster.orders.service;

import com.dinemaster.restaurant.entity.Restaurant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class OrderNumberService {
    private final JdbcTemplate jdbc;
    public OrderNumberService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public String next(Restaurant branch) {
        ZoneId zone;
        try { zone = ZoneId.of(branch.getTimezone()); } catch (Exception ignored) { zone = ZoneId.of("Asia/Kolkata"); }
        LocalDate day = LocalDate.now(zone);
        Integer sequence = jdbc.queryForObject("""
                INSERT INTO order_number_counters(branch_id, business_date, last_value) VALUES (?, ?, 1)
                ON CONFLICT (branch_id, business_date) DO UPDATE SET last_value = order_number_counters.last_value + 1
                RETURNING last_value
                """, Integer.class, branch.getId(), day);
        String code = branch.getCode() == null || branch.getCode().isBlank() ? "BR" : branch.getCode().trim();
        if (code.length() > 12) code = code.substring(0, 12);
        return code + "-" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%04d", sequence);
    }
}
