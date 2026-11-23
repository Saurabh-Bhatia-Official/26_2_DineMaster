package com.dinemaster.kitchen.controller;



import com.dinemaster.kitchen.entity.Kot;
import com.dinemaster.kitchen.repository.KotRepository;
import com.dinemaster.kitchen.service.KitchenService;
import com.dinemaster.kitchen.service.KitchenEventService;
import com.dinemaster.kitchen.service.KotQueryService;
import com.dinemaster.kitchen.dto.KitchenItemStatusRequest;
import com.dinemaster.kitchen.dto.KitchenPriorityRequest;
import com.dinemaster.kitchen.dto.KitchenDelayRequest;
import com.dinemaster.kitchen.dto.AssignChefRequest;
import com.dinemaster.orders.entity.Order;
import com.dinemaster.orders.entity.OrderItem;
import com.dinemaster.orders.repository.OrderRepository;
import com.dinemaster.security.BranchScopeService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Lazy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/kitchen-tickets")
public class KotController {
    private final KotRepository kotRepository;
    private final OrderRepository orderRepository;
    private final BranchScopeService branchScope;
    private final KitchenService kitchen;
    private final KotQueryService queries;
    private final KitchenEventService events;

    public KotController(KotRepository kotRepository, OrderRepository orderRepository,
            BranchScopeService branchScope,
            KitchenService kitchen, KotQueryService queries, KitchenEventService events) {
        this.kotRepository = kotRepository;
        this.orderRepository = orderRepository;
        this.branchScope = branchScope;
        this.kitchen = kitchen; this.queries = queries; this.events = events;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('kot:view')")
    public List<Map<String, Object>> listByRestaurant(@RequestParam Long restaurantId,
            @RequestParam(required = false) String station, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "oldest") String sort, Authentication authentication) {
        branchScope.requireBranch(authentication, restaurantId);
        return queries.board(restaurantId, station, status, sort, false);
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('kot:view')")
    public List<Map<String, Object>> history(@RequestParam Long restaurantId,
            @RequestParam(required = false) String station, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "oldest") String sort, Authentication authentication) {
        branchScope.requireBranch(authentication, restaurantId);
        return queries.board(restaurantId, station, status, sort, true);
    }

    @GetMapping("/events")
    @PreAuthorize("hasAuthority('kot:view')")
    public List<Map<String, Object>> events(@RequestParam Long restaurantId, @RequestParam(defaultValue = "0") Long afterSeq,
            @RequestParam(required = false) String station, @RequestParam(defaultValue = "200") int limit,
            Authentication authentication) {
        branchScope.requireBranch(authentication, restaurantId);
        return events.replay(restaurantId, afterSeq, station, limit);
    }

    @PutMapping("/{kotId}/items/{itemId}/status")
    @PreAuthorize("hasAuthority('kot:update_status')")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateItemStatus(@PathVariable Long kotId, @PathVariable Long itemId,
            @Valid @RequestBody KitchenItemStatusRequest request, Authentication authentication) {
        Kot kot = kotRepository.findById(kotId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Kitchen ticket not found"));
        branchScope.requireBranch(authentication, kot.getBranchId());
        OrderItem changed = kitchen.changeItemStatus(kotId, itemId, request.status(), request.reason(), branchScope.requireUser(authentication));
        return ResponseEntity.ok(Map.of("kotId", kotId, "itemId", itemId, "status", changed.getStatus(),
                "acceptedAt", changed.getAcceptedAt() == null ? "" : changed.getAcceptedAt(),
                "readyAt", changed.getReadyAt() == null ? "" : changed.getReadyAt()));
    }

    @PostMapping("/{kotId}/priority")
    @PreAuthorize("hasAuthority('kot:update_status')")
    public ResponseEntity<Void> setPriority(@PathVariable Long kotId, @Valid @RequestBody KitchenPriorityRequest request,
            Authentication authentication) {
        Kot kot = kotRepository.findById(kotId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Kitchen ticket not found"));
        branchScope.requireBranch(authentication, kot.getBranchId());
        kitchen.setPriority(kotId, request.priority(), branchScope.requireUser(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{kotId}/delay-reason")
    @PreAuthorize("hasAuthority('kot:update_status')")
    public ResponseEntity<Void> delayReason(@PathVariable Long kotId, @Valid @RequestBody KitchenDelayRequest request,
            Authentication authentication) {
        Kot kot = kotRepository.findById(kotId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Kitchen ticket not found"));
        branchScope.requireBranch(authentication, kot.getBranchId());
        kitchen.recordDelay(kotId, request.reason(), branchScope.requireUser(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{kotId}/assign-chef")
    @PreAuthorize("hasAuthority('kot:update_status')")
    public ResponseEntity<Void> assignChef(@PathVariable Long kotId, @Valid @RequestBody AssignChefRequest request,
            Authentication authentication) {
        Kot kot = kotRepository.findById(kotId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Kitchen ticket not found"));
        branchScope.requireBranch(authentication, kot.getBranchId());
        kitchen.assignChef(kotId, request.chefId(), branchScope.requireUser(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{kotId}/delays")
    @PreAuthorize("hasAuthority('kot:view')")
    public List<Map<String, Object>> delayHistory(@PathVariable Long kotId, Authentication authentication) {
        Kot kot = kotRepository.findById(kotId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Kitchen ticket not found"));
        branchScope.requireBranch(authentication, kot.getBranchId());
        return kitchen.delayHistory(kotId);
    }

    @GetMapping("/order/{orderId}")
    @PreAuthorize("hasAuthority('kot:view')")
    public List<Map<String, Object>> list(@PathVariable Long orderId,
            Authentication authentication) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        branchScope.requireBranch(authentication, order.getRestaurant().getId());
        return kotRepository.findByOrder_IdOrderByCreatedAtAsc(orderId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private Map<String, Object> toResponse(Kot kot) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", kot.getId());
        response.put("orderId", kot.getOrder() == null ? null : kot.getOrder().getId());
        response.put("kotNumber", kot.getKotNumber());
        response.put("status", kot.getStatus());
        response.put("createdAt", kot.getCreatedAt());
        response.put("startedCookingAt", kot.getStartedCookingAt());
        response.put("readyAt", kot.getReadyAt());
        response.put("servedAt", kot.getServedAt());
        response.put("delayReason", kot.getDelayReason());
        response.put("chefName", kot.getChefName());
        response.put("priority", kot.getPriority());
        response.put("estimatedTime", kot.getEstimatedTime());
        return response;
    }
}
