package com.dinemaster.diningtable.service;

import com.dinemaster.diningtable.entity.DiningTable;
import com.dinemaster.diningtable.repository.DiningTableRepository;
import com.dinemaster.orders.repository.OrderItemRepository;
import com.dinemaster.orders.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Locale;
import java.util.Set;

@Service
public class TableWorkflowService {
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final DiningTableRepository tables;
    private final JdbcTemplate jdbc;
    public TableWorkflowService(OrderRepository orders, OrderItemRepository items, DiningTableRepository tables, JdbcTemplate jdbc) {
        this.orders = orders; this.items = items; this.tables = tables; this.jdbc = jdbc;
    }
    @Transactional
    public void markServedIfOrderComplete(Long orderId, Long actorId) {
        var order = orders.findById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (order.getDiningTable() == null) return;
        var lines = items.findByOrder_Id(orderId);
        if (lines.isEmpty() || lines.stream().anyMatch(i -> !Set.of("SERVED", "CANCELLED").contains(i.getStatus().toUpperCase(Locale.ROOT)))) return;
        DiningTable table = tables.findForUpdateById(order.getDiningTable().getId()).orElseThrow();
        setStatus(table, "SERVED", actorId, "All order items served or cancelled");
    }
    @Transactional
    public void requestBill(Long tableId, Long actorId) {
        DiningTable table = tables.findForUpdateById(tableId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found"));
        if ("BILL_REQUESTED".equalsIgnoreCase(table.getStatus())) return;
        if (!Set.of("OCCUPIED", "ORDERING", "FOOD_PENDING", "PREPARING", "SERVED")
                .contains(table.getStatus().toUpperCase(Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Table must have an active order before bill request");
        setStatus(table, "BILL_REQUESTED", actorId, "Waiter requested bill");
    }
    private void setStatus(DiningTable table, String next, Long actorId, String reason) {
        String from = table.getStatus();
        if (next.equalsIgnoreCase(from)) return;
        table.setStatus(next); tables.save(table);
        jdbc.update("INSERT INTO table_status_logs(table_id, from_status, to_status, actor_id, reason) VALUES (?, ?, ?, ?, ?)",
                table.getId(), from, next, actorId, reason);
    }
}
