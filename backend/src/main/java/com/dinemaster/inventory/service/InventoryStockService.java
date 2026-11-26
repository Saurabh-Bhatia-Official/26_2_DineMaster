package com.dinemaster.inventory.service;
import com.dinemaster.inventory.entity.InventoryItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
@Service
public class InventoryStockService {
    private final JdbcTemplate jdbc;
    public InventoryStockService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional
    public void reconcile(InventoryItem item, BigDecimal before, BigDecimal after, String reason, String refType, Long refId) {
        BigDecimal delta = after.subtract(before);
        if (delta.signum() > 0) receive(item, delta, item.getExpiryDate(), item.getUnitCost(), reason, refType, refId);
        else if (delta.signum() < 0) consume(item, delta.abs(), reason, refType, refId);
        item.setCurrentStock(after);
    }
    @Transactional
    public void receive(InventoryItem item, BigDecimal quantity, LocalDate expiry, BigDecimal unitCost,
            String reason, String refType, Long refId) {
        if (quantity.signum() <= 0) return;
        Long batchId = jdbc.queryForObject("""
                INSERT INTO stock_batches(inventory_id, quantity_on_hand, expiry_date, unit_cost)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, item.getId(), quantity, expiry == null ? null : Date.valueOf(expiry), unitCost);
        movement(item.getId(), batchId, quantity, reason, refType, refId);
    }
    @Transactional
    public void consume(InventoryItem item, BigDecimal quantity, String reason, String refType, Long refId) {
        BigDecimal remaining = quantity;
        List<Map<String, Object>> batches = jdbc.queryForList("""
                SELECT id, quantity_on_hand FROM stock_batches
                WHERE inventory_id = ? AND quantity_on_hand > 0
                ORDER BY expiry_date ASC NULLS LAST, received_at ASC, id ASC FOR UPDATE
                """, item.getId());
        for (Map<String, Object> batch : batches) {
            if (remaining.signum() <= 0) break;
            Long batchId = ((Number) batch.get("id")).longValue();
            BigDecimal available = (BigDecimal) batch.get("quantity_on_hand");
            BigDecimal used = available.min(remaining);
            jdbc.update("UPDATE stock_batches SET quantity_on_hand = quantity_on_hand - ? WHERE id = ?", used, batchId);
            movement(item.getId(), batchId, used.negate(), reason, refType, refId);
            remaining = remaining.subtract(used);
        }
        if (remaining.signum() > 0) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Insufficient stock for ingredient " + item.getItemName());
        item.setCurrentStock(item.getCurrentStock().subtract(quantity));
    }
    private void movement(Long inventoryId, Long batchId, BigDecimal delta, String reason, String refType, Long refId) {
        jdbc.update("""
                INSERT INTO stock_movements(inventory_id, batch_id, delta, reason, ref_type, ref_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, inventoryId, batchId, delta, reason, refType, refId);
    }
}
