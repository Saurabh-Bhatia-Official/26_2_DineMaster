package com.dinemaster.kitchen.controller;

import com.dinemaster.kitchen.dto.ReprintRequest;
import com.dinemaster.kitchen.entity.Kot;
import com.dinemaster.kitchen.entity.KotItem;
import com.dinemaster.kitchen.entity.PrintJob;
import com.dinemaster.kitchen.repository.KotItemRepository;
import com.dinemaster.kitchen.repository.KotRepository;
import com.dinemaster.kitchen.repository.PrintJobRepository;
import com.dinemaster.kitchen.service.PrintJobService;
import com.dinemaster.orders.service.OrderAuditService;
import com.dinemaster.orders.repository.OrderRepository;
import com.dinemaster.security.BranchScopeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class M13KotController {
    private final KotRepository kots;
    private final KotItemRepository items;
    private final OrderRepository orders;
    private final BranchScopeService branchScope;
    private final PrintJobService printJobs;
    private final OrderAuditService audit;
    public M13KotController(KotRepository kots, KotItemRepository items,
            OrderRepository orders, BranchScopeService branchScope, PrintJobService printJobs, OrderAuditService audit) {
        this.kots = kots; this.items = items; this.orders = orders; this.branchScope = branchScope;
        this.printJobs = printJobs; this.audit = audit;
    }
    @GetMapping("/kots/{id}")
    @PreAuthorize("hasAuthority('kot:view')")
    @Transactional(readOnly = true)
    public ResponseEntity<?> get(@PathVariable Long id, Authentication auth) {
        Kot kot = kots.findById(id).orElse(null);
        if (kot == null) return ResponseEntity.notFound().build();
        branchScope.requireBranch(auth, kot.getBranchId());
        return ResponseEntity.ok(toResponse(kot));
    }
    @GetMapping("/orders/{id}/kots")
    @PreAuthorize("hasAuthority('kot:view')")
    @Transactional(readOnly = true)
    public ResponseEntity<?> byOrder(@PathVariable Long id, Authentication auth) {
        var order = orders.findById(id).orElse(null);
        if (order == null) return ResponseEntity.notFound().build();
        branchScope.requireBranch(auth, order.getRestaurant().getId());
        return ResponseEntity.ok(kots.findByOrder_IdOrderByCreatedAtAsc(id).stream()
                .sorted(java.util.Comparator.comparing(Kot::getRoundNo).thenComparing(Kot::getStationKey).thenComparing(Kot::getCreatedAt))
                .map(this::toResponse).toList());
    }
    @PostMapping("/kots/{id}/reprint")
    @PreAuthorize("hasAuthority('kot:reprint')")
    @Transactional
    public ResponseEntity<?> reprint(@PathVariable Long id, @Valid @RequestBody ReprintRequest request, Authentication auth) {
        Kot kot = kots.findById(id).orElse(null);
        if (kot == null) return ResponseEntity.notFound().build();
        branchScope.requireBranch(auth, kot.getBranchId());
        PrintJob job = printJobs.enqueueKot(kot, branchScope.requireUser(auth), request.reason().trim(), request.printerId(), true);
        audit.transition(kot.getOrder().getId(), "ORDER", kot.getOrder().getId(), kot.getOrder().getStatus(),
                kot.getOrder().getStatus(), branchScope.requireUser(auth).getId(), null, "KOT reprint: " + request.reason().trim(), null);
        return ResponseEntity.accepted().body(Map.of("printJobId", job.getId(), "kotId", kot.getId(),
                "status", job.getStatus(), "stamp", "DUPLICATE"));
    }
    @GetMapping("/print-jobs/failed")
    @PreAuthorize("hasAuthority('kot:view')")
    public ResponseEntity<?> failed(@RequestParam Long branchId, Authentication auth) {
        branchScope.requireBranch(auth, branchId);
        return ResponseEntity.ok(printJobs.failed(branchId).stream()
                .map(j -> Map.of("id", j.getId(), "kotId", j.getRefId(), "attempts", j.getAttempts(),
                        "error", j.getError() == null ? "" : j.getError(), "status", j.getStatus())).toList());
    }
    @PostMapping("/print-jobs/{id}/retry")
    @PreAuthorize("hasAuthority('kot:reprint')")
    public ResponseEntity<?> retry(@PathVariable Long id, @RequestParam Long branchId, Authentication auth) {
        branchScope.requireBranch(auth, branchId);
        PrintJob job = printJobs.retry(id, branchId);
        return ResponseEntity.accepted().body(Map.of("id", job.getId(), "status", job.getStatus(), "attempts", job.getAttempts()));
    }
    private Map<String, Object> toResponse(Kot kot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", kot.getId()); result.put("kotNumber", kot.getKotNumber());
        result.put("orderId", kot.getOrder().getId()); result.put("branchId", kot.getBranchId());
        result.put("stationId", kot.getStationKey()); result.put("roundNo", kot.getRoundNo());
        result.put("isUrgent", kot.isUrgent()); result.put("createdAt", kot.getCreatedAt());
        result.put("ticketKind", kot.getTicketKind()); result.put("station", kot.getStationKey());
        result.put("items", items.findByKot_IdOrderByIdAsc(kot.getId()).stream().map(this::itemResponse).toList());
        return result;
    }
    private Map<String, Object> itemResponse(KotItem item) {
        return Map.of("orderItemId", item.getOrderItemId(), "productId", item.getProductId(),
                "itemName", item.getItemName(), "quantity", item.getQuantity(),
                "instructions", item.getInstructions() == null ? "" : item.getInstructions());
    }
}
