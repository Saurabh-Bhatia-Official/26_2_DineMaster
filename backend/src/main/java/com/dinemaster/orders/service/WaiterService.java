package com.dinemaster.orders.service;

import com.dinemaster.diningtable.service.TableWorkflowService;
import com.dinemaster.orders.entity.Order;
import com.dinemaster.orders.repository.OrderRepository;
import com.dinemaster.user.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@Service
public class WaiterService {
    private final OrderRepository orders;
    private final TableWorkflowService tables;
    private final OrderAuditService audit;
    private final ApplicationEventPublisher publisher;
    public WaiterService(OrderRepository orders, TableWorkflowService tables,
            OrderAuditService audit, ApplicationEventPublisher publisher) {
        this.orders = orders; this.tables = tables; this.audit = audit; this.publisher = publisher;
    }
    @Transactional
    public Map<String, Object> requestBill(Long orderId, User actor) {
        Order order = orders.findForUpdateById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        boolean manager = com.dinemaster.role.Roles.matches(actor.getRole(), "MANAGER")
                || com.dinemaster.role.Roles.matches(actor.getRole(), "OWNER");
        boolean waiterOwns = com.dinemaster.role.Roles.matches(actor.getRole(), "WAITER")
                && ((order.getWaiter() != null && order.getWaiter().getId().equals(actor.getId()))
                    || (order.getCreatedBy() != null && order.getCreatedBy().getId().equals(actor.getId())));
        if (!manager && !waiterOwns) throw new org.springframework.security.access.AccessDeniedException("Waiter can request bills only for their own orders");
        if (order.getDiningTable() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Bill request requires a table order");
        if (!"CONFIRMED".equalsIgnoreCase(order.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a confirmed order can request a bill");
        var table = order.getDiningTable();
        if (!manager && (table.getWaiter() == null || !table.getWaiter().getId().equals(actor.getId())))
            throw new org.springframework.security.access.AccessDeniedException("Table is assigned to another waiter");
        boolean duplicate = "BILL_REQUESTED".equalsIgnoreCase(table.getStatus());
        tables.requestBill(table.getId(), actor.getId());
        if (!duplicate) audit.event(orderId, "bill.requested", Map.of("orderId", orderId, "tableId", table.getId(),
                "branchId", order.getRestaurant().getId(), "requestedBy", actor.getId()));
        if (!duplicate) publisher.publishEvent(new BillRequestedEvent(order.getRestaurant().getId(), orderId,
                table.getId(), actor.getId(), order.getOrderNo()));
        return Map.of("orderId", orderId, "tableId", table.getId(), "tableStatus", "BILL_REQUESTED", "alreadyRequested", duplicate);
    }
}
