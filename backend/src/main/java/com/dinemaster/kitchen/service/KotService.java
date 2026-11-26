package com.dinemaster.kitchen.service;

import com.dinemaster.kitchen.entity.Kot;
import com.dinemaster.kitchen.entity.KotItem;
import com.dinemaster.kitchen.repository.KotItemRepository;
import com.dinemaster.kitchen.repository.KotRepository;
import com.dinemaster.orders.entity.Order;
import com.dinemaster.orders.entity.OrderItem;
import com.dinemaster.orders.repository.OrderItemRepository;
import com.dinemaster.orders.repository.OrderRepository;
import com.dinemaster.orders.service.OrderAuditService;
import com.dinemaster.user.entity.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class KotService {
    private final KotRepository kots;
    private final KotItemRepository items;
    private final OrderItemRepository orderItems;
    private final OrderRepository orders;
    private final JdbcTemplate jdbc;
    private final OrderAuditService audit;
    private final KitchenEventService kitchenEvents;
    private final PrintJobService printJobs;
    public KotService(KotRepository kots, KotItemRepository items, OrderItemRepository orderItems,
            OrderRepository orders, JdbcTemplate jdbc, OrderAuditService audit, KitchenEventService kitchenEvents,
            PrintJobService printJobs) {
        this.kots = kots; this.items = items; this.orderItems = orderItems; this.orders = orders; this.jdbc = jdbc;
        this.audit = audit; this.kitchenEvents = kitchenEvents; this.printJobs = printJobs;
    }

    /** Called by the order workflow inside its confirmation transaction. Unassigned items form a new round. */
    @Transactional
    public List<Kot> generate(Long orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (order.getRestaurant() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order has no branch");
        List<OrderItem> pending = orderItems.findByOrder_Id(orderId).stream()
                .filter(i -> i.getKot() == null && !"CANCELLED".equalsIgnoreCase(i.getStatus())).toList();
        if (pending.isEmpty()) return List.of();
        int round = kots.findByOrder_IdOrderByCreatedAtAsc(orderId).stream().map(Kot::getRoundNo)
                .filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
        LocalDate today = businessDate(order);
        java.util.Map<String, List<OrderItem>> byStation = pending.stream().collect(java.util.stream.Collectors.groupingBy(
                item -> item.getStation() == null || item.getStation().isBlank() ? "GENERAL" : item.getStation().trim(),
                java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        java.util.List<Kot> generated = new java.util.ArrayList<>();
        for (var station : byStation.entrySet()) {
            Kot kot = new Kot();
            kot.setOrder(order); kot.setBranchId(order.getRestaurant().getId()); kot.setRoundNo(round);
            kot.setStationKey(station.getKey());
            kot.setKotNumber(nextNumber(order, today));
            kot = kots.saveAndFlush(kot);
            for (OrderItem source : station.getValue()) {
                KotItem snapshot = new KotItem(); snapshot.setKot(kot); snapshot.setOrderItemId(source.getId());
                snapshot.setProductId(source.getProduct().getId());
                snapshot.setItemName(source.getProductNameSnapshot() == null ? source.getProduct().getName() : source.getProductNameSnapshot());
                snapshot.setQuantity(source.getQuantity()); snapshot.setInstructions(source.getNotes()); items.save(snapshot);
                source.setKot(kot); source.setStatus("PENDING");
            }
            generated.add(kot);
            List<java.util.Map<String, Object>> eventItems = station.getValue().stream().map(item -> java.util.Map.<String, Object>of(
                    "itemId", item.getId(), "name", item.getProductNameSnapshot(), "quantity", item.getQuantity(),
                    "notes", item.getNotes() == null ? "" : item.getNotes())).toList();
            kitchenEvents.publishAfterCommit(order.getRestaurant().getId(), station.getKey(), "kot.created",
                    java.util.Map.of("kotId", kot.getId(), "kotNumber", kot.getKotNumber(), "orderId", orderId,
                            "orderNo", order.getOrderNo() == null ? "" : order.getOrderNo(), "round", round, "items", eventItems));
            audit.event(orderId, "kot.created", java.util.Map.of("kotId", kot.getId(), "station", station.getKey(), "round", round));
            printJobs.enqueueKot(kot, null, null, null, false);
        }
        return generated;
    }

    /** Create immutable cancellation notices without changing the original KOT snapshots. */
    @Transactional
    public List<Kot> createCancellationNotices(Long orderId, List<OrderItem> canceledItems, String reason, User actor) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        List<OrderItem> ticketed = canceledItems.stream().filter(i -> i.getKot() != null).toList();
        if (ticketed.isEmpty()) return List.of();
        LocalDate today = businessDate(order);
        java.util.Map<String, List<OrderItem>> byStation = ticketed.stream().collect(java.util.stream.Collectors.groupingBy(
                i -> i.getStation() == null || i.getStation().isBlank() ? "GENERAL" : i.getStation().trim(),
                java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        int round = kots.findByOrder_IdOrderByCreatedAtAsc(orderId).stream().map(Kot::getRoundNo)
                .filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(1);
        List<Kot> notices = new java.util.ArrayList<>();
        for (var station : byStation.entrySet()) {
            Kot notice = new Kot(); notice.setOrder(order); notice.setBranchId(order.getRestaurant().getId());
            notice.setStationKey(station.getKey()); notice.setRoundNo(round); notice.setTicketKind("CANCEL_NOTICE");
            notice.setKotNumber(nextNumber(order, today)); notice.setPriority("URGENT"); notice.setUrgent(true);
            notice = kots.saveAndFlush(notice);
            for (OrderItem source : station.getValue()) {
                KotItem snapshot = new KotItem(); snapshot.setKot(notice); snapshot.setOrderItemId(source.getId());
                snapshot.setProductId(source.getProduct().getId()); snapshot.setItemName(source.getProductNameSnapshot());
                snapshot.setQuantity(source.getQuantity());
                String instructions = source.getNotes() == null ? "" : source.getNotes() + " | ";
                snapshot.setInstructions(instructions + "CANCELLED: " + reason.trim()); items.save(snapshot);
            }
            notices.add(notice);
            List<java.util.Map<String, Object>> canceled = station.getValue().stream().map(item -> java.util.Map.<String, Object>of(
                    "itemId", item.getId(), "name", item.getProductNameSnapshot(), "quantity", item.getQuantity())).toList();
            kitchenEvents.publishAfterCommit(order.getRestaurant().getId(), station.getKey(), "kot.cancel_notice_created",
                    java.util.Map.of("kotId", notice.getId(), "kotNumber", notice.getKotNumber(), "orderId", orderId,
                            "round", round, "reason", reason.trim(), "items", canceled));
            audit.event(orderId, "kot.cancel_notice_created", java.util.Map.of("kotId", notice.getId(), "station", station.getKey()));
            audit.transition(orderId, "ITEM", station.getValue().getFirst().getId(),
                    station.getValue().getFirst().getStatus(), "CANCELLED", actor.getId(), null, reason.trim(), null);
            printJobs.enqueueKot(notice, actor, null, null, false);
        }
        return notices;
    }

    private LocalDate businessDate(Order order) {
        try { return LocalDate.now(java.time.ZoneId.of(order.getRestaurant().getTimezone())); }
        catch (Exception ignored) { return LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")); }
    }

    private String nextNumber(Order order, LocalDate day) {
        Integer sequence = jdbc.queryForObject("""
                INSERT INTO kot_daily_counters(branch_id, ticket_date, last_value) VALUES (?, ?, 1)
                ON CONFLICT (branch_id, ticket_date) DO UPDATE SET last_value = kot_daily_counters.last_value + 1
                RETURNING last_value
                """, Integer.class, order.getRestaurant().getId(), day);
        return "K-" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%04d", sequence);
    }
}
