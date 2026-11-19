package com.dinemaster.kitchen.service;

import com.dinemaster.kitchen.entity.Kot;
import com.dinemaster.diningtable.service.TableWorkflowService;
import com.dinemaster.kitchen.repository.KotRepository;
import com.dinemaster.orders.entity.OrderItem;
import com.dinemaster.orders.repository.OrderItemRepository;
import com.dinemaster.orders.service.OrderAuditService;
import com.dinemaster.user.entity.User;
import com.dinemaster.user.repository.UserBranchRepository;
import com.dinemaster.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;

@Service
public class KitchenService {
    private final KotRepository kots;
    private final OrderItemRepository items;
    private final UserRepository users;
    private final UserBranchRepository userBranches;
    private final OrderAuditService audit;
    private final KitchenEventService events;
    private final JdbcTemplate jdbc;
    private final TableWorkflowService tableWorkflow;
    private final int delayThresholdMinutes;

    public KitchenService(KotRepository kots, OrderItemRepository items, UserRepository users,
            UserBranchRepository userBranches, OrderAuditService audit, KitchenEventService events,
            JdbcTemplate jdbc, TableWorkflowService tableWorkflow,
            @Value("${dinemaster.kitchen.delay-threshold-minutes:15}") int delayThresholdMinutes) {
        this.kots = kots; this.items = items; this.users = users; this.userBranches = userBranches;
        this.audit = audit; this.events = events; this.jdbc = jdbc;
        this.tableWorkflow = tableWorkflow;
        this.delayThresholdMinutes = Math.max(1, delayThresholdMinutes);
    }

    @Transactional
    public OrderItem changeItemStatus(Long kotId, Long itemId, String requestedStatus, String reason, User actor) {
        Kot kot = requireKot(kotId);
        OrderItem item = items.findForUpdateByIdAndKotId(itemId, kotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket item not found"));
        String from = item.getStatus().toUpperCase(Locale.ROOT);
        String to = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase(Locale.ROOT);
        boolean chef = com.dinemaster.role.Roles.matches(actor.getRole(), "CHEF");
        boolean waiter = com.dinemaster.role.Roles.matches(actor.getRole(), "WAITER");
        boolean manager = isManager(actor);
        boolean allowed = (from.equals("PENDING") && to.equals("COOKING") && (chef || manager))
                || (from.equals("COOKING") && to.equals("READY") && (chef || manager))
                || (from.equals("READY") && to.equals("SERVED") && (waiter || manager))
                || (manager && (to.equals("PENDING") || to.equals("COOKING")) && !to.equals(from));
        if (!allowed) throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid or unauthorized kitchen item transition");
        if (manager && (to.equals("PENDING") || from.equals("READY") && to.equals("COOKING"))
                && (reason == null || reason.isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required for a kitchen override");
        OffsetDateTime now = OffsetDateTime.now();
        if (to.equals("COOKING")) {
            item.setAcceptedAt(now); item.setChef(actor);
            kot.setAssignedChef(actor); kot.setChefName(actor.getName()); kots.save(kot);
        }
        if (to.equals("READY")) item.setReadyAt(now);
        item.setStatus(to);
        items.saveAndFlush(item);
        audit.transition(kot.getOrder().getId(), "ITEM", item.getId(), from, to, actor.getId(),
                manager && !chef && !waiter ? actor.getId() : null, reason, null);
        Map<String, Object> payload = Map.of("kotId", kotId, "itemId", itemId, "orderId", kot.getOrder().getId(),
                "from", from, "to", to, "by", actor.getId());
        events.publishAfterCommit(kot.getBranchId(), kot.getStationKey(), "kot.item_status_changed", payload);
        audit.event(kot.getOrder().getId(), "kot.item_status_changed", payload);
        if (to.equals("SERVED")) tableWorkflow.markServedIfOrderComplete(kot.getOrder().getId(), actor.getId());
        return item;
    }

    @Transactional
    public void setPriority(Long kotId, String priority, User actor) {
        requireManager(actor);
        Kot kot = requireKot(kotId);
        String value = priority.trim().toUpperCase(Locale.ROOT);
        if (!value.equals("NORMAL") && !value.equals("URGENT") && !value.equals("HIGH"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Priority must be NORMAL, HIGH or URGENT");
        String prior = kot.getPriority(); kot.setPriority(value); kot.setUrgent(!value.equals("NORMAL")); kots.save(kot);
        Map<String, Object> payload = Map.of("kotId", kotId, "from", prior, "priority", value, "urgent", kot.isUrgent(), "by", actor.getId());
        events.publishAfterCommit(kot.getBranchId(), kot.getStationKey(), "kot.priority_changed", payload);
        audit.event(kot.getOrder().getId(), "kot.priority_changed", payload);
    }

    @Transactional
    public void recordDelay(Long kotId, String reason, User actor) {
        Kot kot = requireKot(kotId);
        if (reason == null || reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Delay reason is required");
        int minutes = (int) Math.max(1, Duration.between(kot.getCreatedAt(), OffsetDateTime.now()).toMinutes());
        if (minutes < delayThresholdMinutes && !isManager(actor))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delay reason can be submitted after " + delayThresholdMinutes + " minutes");
        jdbc.update("INSERT INTO kitchen_delays(kot_id, reason, minutes, recorded_by) VALUES (?, ?, ?, ?)",
                kotId, reason.trim(), minutes, actor.getId());
        kot.setDelayReason(reason.trim()); kots.save(kot);
        Map<String, Object> payload = Map.of("kotId", kotId, "minutes", minutes, "reason", reason.trim(), "by", actor.getId());
        events.publishAfterCommit(kot.getBranchId(), kot.getStationKey(), "kot.delay_alert", payload);
        audit.event(kot.getOrder().getId(), "kot.delay_reason_recorded", payload);
    }

    @Transactional
    public void assignChef(Long kotId, Long chefId, User actor) {
        requireManager(actor);
        Kot kot = requireKot(kotId);
        User chef = users.findById(chefId).filter(User::isActive)
                .filter(u -> com.dinemaster.role.Roles.matches(u.getRole(), "CHEF"))
                .filter(u -> java.util.Objects.equals(u.getTenantId(), kot.getOrder().getRestaurant().getTenantId()))
                .filter(u -> userBranches.isAssigned(u.getId(), kot.getBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chef not found for this branch"));
        kot.setAssignedChef(chef); kot.setChefName(chef.getName()); kots.save(kot);
        Map<String, Object> payload = Map.of("kotId", kotId, "chefId", chef.getId(), "chefName", chef.getName(), "by", actor.getId());
        events.publishAfterCommit(kot.getBranchId(), kot.getStationKey(), "kot.chef_assigned", payload);
        audit.event(kot.getOrder().getId(), "kot.chef_assigned", payload);
    }

    @Transactional(readOnly = true)
    public java.util.List<Map<String, Object>> delayHistory(Long kotId) {
        requireKot(kotId);
        return jdbc.queryForList("SELECT id, reason, minutes, recorded_by AS recordedBy, created_at AS createdAt FROM kitchen_delays WHERE kot_id = ? ORDER BY created_at DESC", kotId);
    }

    private Kot requireKot(Long id) {
        return kots.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kitchen ticket not found"));
    }
    private boolean isManager(User user) {
        return com.dinemaster.role.Roles.matches(user.getRole(), "MANAGER") || com.dinemaster.role.Roles.matches(user.getRole(), "OWNER");
    }
    private void requireManager(User user) {
        if (!isManager(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Manager permission is required");
    }
}
