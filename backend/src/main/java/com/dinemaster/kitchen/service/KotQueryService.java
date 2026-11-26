package com.dinemaster.kitchen.service;

import com.dinemaster.kitchen.entity.Kot;
import com.dinemaster.kitchen.repository.KotItemRepository;
import com.dinemaster.kitchen.repository.KotRepository;
import com.dinemaster.orders.entity.OrderItem;
import com.dinemaster.orders.repository.OrderItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class KotQueryService {
    private final KotRepository kots;
    private final OrderItemRepository orderItems;
    private final KotItemRepository snapshots;
    public KotQueryService(KotRepository kots, OrderItemRepository orderItems, KotItemRepository snapshots) {
        this.kots = kots; this.orderItems = orderItems; this.snapshots = snapshots;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> board(Long branchId, String station, String status, String sort, boolean history) {
        var rows = kots.findByOrder_Restaurant_IdOrderByCreatedAtDesc(branchId).stream()
                .filter(k -> station == null || station.isBlank() || station.equalsIgnoreCase(k.getStationKey()))
                .map(this::toKitchenCard)
                .filter(card -> history || !List.of("SERVED", "CANCELLED").contains(card.get("status")))
                .filter(card -> status == null || status.isBlank() || status.equalsIgnoreCase(String.valueOf(card.get("status"))))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        Comparator<Map<String, Object>> ordering = switch (sort == null ? "oldest" : sort.toLowerCase(Locale.ROOT)) {
            case "priority" -> Comparator.<Map<String, Object>, Boolean>comparing(m -> Boolean.TRUE.equals(m.get("urgent"))).reversed()
                    .thenComparing(m -> (OffsetDateTime) m.get("createdAt"));
            case "table" -> Comparator.<Map<String, Object>, String>comparing(m -> String.valueOf(m.get("tableName")), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(m -> (OffsetDateTime) m.get("createdAt"));
            case "fastest", "fastest_prep" -> Comparator.<Map<String, Object>, Integer>comparing(m -> (Integer) m.get("estimatedMinutes"))
                    .thenComparing(m -> (OffsetDateTime) m.get("createdAt"));
            default -> Comparator.comparing(m -> (OffsetDateTime) m.get("createdAt"));
        };
        rows.sort(ordering);
        return List.copyOf(rows);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> toKitchenCard(Kot kot) {
        List<OrderItem> lines = orderItems.findByKot_Id(kot.getId());
        List<String> states = lines.stream().map(i -> i.getStatus().toUpperCase(Locale.ROOT)).toList();
        String derived = "CANCEL_NOTICE".equals(kot.getTicketKind()) ? "CANCELLATION_NOTICE"
                : states.isEmpty() ? "PENDING"
                : states.stream().allMatch("CANCELLED"::equals) ? "CANCELLED"
                : states.stream().allMatch(s -> s.equals("SERVED") || s.equals("CANCELLED")) ? "SERVED"
                : states.stream().allMatch(s -> s.equals("READY") || s.equals("SERVED") || s.equals("CANCELLED")) ? "READY"
                : states.stream().anyMatch("COOKING"::equals) ? "COOKING" : "PENDING";
        List<Map<String, Object>> items = snapshots.findByKot_IdOrderByIdAsc(kot.getId()).stream().map(snapshot -> {
            OrderItem source = lines.stream().filter(i -> i.getId().equals(snapshot.getOrderItemId())).findFirst().orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", snapshot.getOrderItemId()); row.put("name", snapshot.getItemName());
            row.put("quantity", snapshot.getQuantity()); row.put("notes", snapshot.getInstructions());
            row.put("status", "CANCEL_NOTICE".equals(kot.getTicketKind()) ? "CANCELLED"
                    : source == null ? "PENDING" : source.getStatus().toUpperCase(Locale.ROOT));
            row.put("acceptedAt", source == null ? null : source.getAcceptedAt());
            row.put("readyAt", source == null ? null : source.getReadyAt());
            row.put("chefName", source == null || source.getChef() == null ? null : source.getChef().getName());
            row.put("prepSeconds", source == null || source.getAcceptedAt() == null || source.getReadyAt() == null
                    ? null : Duration.between(source.getAcceptedAt(), source.getReadyAt()).toSeconds());
            return row;
        }).toList();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("kotId", kot.getId()); card.put("kotNumber", kot.getKotNumber()); card.put("orderId", kot.getOrder().getId());
        card.put("orderNo", kot.getOrder().getOrderNo()); card.put("tableId", kot.getOrder().getDiningTable() == null ? null : kot.getOrder().getDiningTable().getId());
        card.put("tableName", kot.getOrder().getDiningTable() == null ? null : kot.getOrder().getDiningTable().getName());
        card.put("station", kot.getStationKey()); card.put("round", kot.getRoundNo()); card.put("status", derived);
        card.put("ticketKind", kot.getTicketKind());
        card.put("createdAt", kot.getCreatedAt()); card.put("ageMinutes", Math.max(0, Duration.between(kot.getCreatedAt(), OffsetDateTime.now()).toMinutes()));
        card.put("priority", kot.getPriority()); card.put("urgent", kot.isUrgent()); card.put("estimatedMinutes", kot.getEstimatedTime());
        card.put("assignedChef", kot.getAssignedChef() == null ? kot.getChefName() : kot.getAssignedChef().getName());
        card.put("items", items);
        return card;
    }
}
