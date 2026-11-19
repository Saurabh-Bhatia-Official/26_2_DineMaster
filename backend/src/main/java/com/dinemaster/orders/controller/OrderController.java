package com.dinemaster.orders.controller;





import com.dinemaster.orders.dto.CreateOrderRequest;
import com.dinemaster.orders.dto.OrderItemResponse;
import com.dinemaster.orders.dto.OrderResponse;
import com.dinemaster.orders.dto.OrderReasonRequest;
import com.dinemaster.orders.dto.PaymentRequest;
import com.dinemaster.orders.dto.CartItemsRequest;
import com.dinemaster.orders.dto.CartItemMutation;
import com.dinemaster.orders.dto.CartResponse;
import com.dinemaster.orders.dto.DiscountRequest;
import com.dinemaster.orders.dto.AttachCustomerRequest;
import com.dinemaster.orders.entity.Order;
import com.dinemaster.orders.entity.OrderItem;
import com.dinemaster.orders.service.OrderService;
import com.dinemaster.orders.service.CartService;
import com.dinemaster.orders.service.OrderAuditService;
import com.dinemaster.orders.service.WaiterService;
import com.dinemaster.restaurant.entity.Restaurant;
import com.dinemaster.security.BranchScopeService;
import com.dinemaster.user.repository.UserBranchRepository;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;
    private final BranchScopeService branchScope;
    private final CartService carts;
    private final OrderAuditService audit;
    private final WaiterService waiterService;
    private final UserBranchRepository userBranches;

    public OrderController(OrderService orderService, BranchScopeService branchScope, CartService carts,
            OrderAuditService audit, WaiterService waiterService, UserBranchRepository userBranches) {
        this.orderService = orderService;
        this.branchScope = branchScope;
        this.carts = carts;
        this.audit = audit;
        this.waiterService = waiterService; this.userBranches = userBranches;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('order:create')")
    public ResponseEntity<CartResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        branchScope.requireBranch(authentication, request.getRestaurantId());
        if (request.getDiscountAmount() != null && request.getDiscountAmount().signum() > 0)
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Apply discounts through the discount endpoint with a required remark");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(carts.createDraft(request.getRestaurantId(), request.getType(), request.getTableId(),
                        request.getCustomerId(), request.getGuestCount(), request.getNotes(),
                        branchScope.requireUser(authentication), request.getItems() == null ? List.of() : request.getItems().stream()
                                .map(i -> new CartItemMutation(i.getProductId(), i.getQuantity(), i.getNotes())).toList(), idempotencyKey));
    }

    @PutMapping("/{id}/items")
    @PreAuthorize("hasAuthority('order:update')")
    public ResponseEntity<CartResponse> replaceCartItems(@PathVariable Long id, @Valid @RequestBody CartItemsRequest request,
            Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(carts.replaceItems(id, request.items() == null ? List.of() : request.items()));
    }

    @PostMapping("/{id}/items")
    @PreAuthorize("hasAuthority('order:update')")
    public ResponseEntity<CartResponse> addCartItem(@PathVariable Long id, @Valid @RequestBody CartItemMutation request,
            Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(carts.addItem(id, request));
    }

    @PostMapping("/{id}/discount")
    @PreAuthorize("hasAuthority('discount:apply')")
    public ResponseEntity<CartResponse> applyDiscount(@PathVariable Long id, @Valid @RequestBody DiscountRequest request,
            Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(carts.applyDiscount(id, request, branchScope.requireUser(authentication)));
    }

    @DeleteMapping("/{id}/discount")
    @PreAuthorize("hasAuthority('discount:apply')")
    public ResponseEntity<CartResponse> clearDiscount(@PathVariable Long id, Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(carts.clearDiscount(id));
    }

    @PostMapping("/{id}/park")
    @PreAuthorize("hasAuthority('order:park')")
    public ResponseEntity<CartResponse> park(@PathVariable Long id, Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(carts.park(id));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('order:reopen')")
    public ResponseEntity<CartResponse> reopen(@PathVariable Long id, Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(carts.reopen(id));
    }

    @PutMapping("/{id}/customer")
    @PreAuthorize("hasAuthority('order:update')")
    public ResponseEntity<CartResponse> attachCustomer(@PathVariable Long id,
            @Valid @RequestBody AttachCustomerRequest request, Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(carts.attachCustomer(id, request.customerId(), branchScope.requireUser(authentication)));
    }

    @GetMapping("/parked")
    @PreAuthorize("hasAuthority('order:view')")
    public ResponseEntity<List<CartResponse>> parked(@RequestParam Long restaurantId, Authentication authentication) {
        branchScope.requireBranch(authentication, restaurantId);
        return ResponseEntity.ok(carts.parked(restaurantId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('order:view')")
    public ResponseEntity<List<OrderResponse>> getOrders(
            @RequestParam(required = false) Long restaurantId, @RequestParam(required = false) String waiterId,
            Authentication authentication) {
        var user = branchScope.requireUser(authentication);
        boolean waiter = com.dinemaster.role.Roles.matches(user.getRole(), "WAITER");
        if (waiter && waiterId != null && !"me".equalsIgnoreCase(waiterId))
            throw new org.springframework.security.access.AccessDeniedException("Waiters may request only waiterId=me");
        List<Long> branchIds;
        if (restaurantId != null) {
            branchScope.requireBranch(authentication, restaurantId);
            branchIds = List.of(restaurantId);
        } else if (waiter) {
            branchIds = userBranches.findBranchIds(user.getId());
            branchIds.forEach(branchId -> branchScope.requireBranch(authentication, branchId));
        } else {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "restaurantId is required for this role");
        }
        var orders = branchIds.stream().flatMap(branchId -> orderService.getOrdersByRestaurant(branchId).stream()).toList();
        if (waiter)
            orders = orders.stream().filter(order -> java.util.Objects.equals(order.waiterId(), user.getId())
                    || java.util.Objects.equals(order.createdById(), user.getId())).toList();
        else if (waiterId != null && !"me".equalsIgnoreCase(waiterId)) {
            Long filterId;
            try { filterId = Long.valueOf(waiterId); }
            catch (NumberFormatException ex) { throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "waiterId must be 'me' or a user id"); }
            orders = orders.stream().filter(order -> java.util.Objects.equals(order.waiterId(), filterId)).toList();
        }
        return ResponseEntity.ok(orders);
    }

    @PostMapping("/{id}/request-bill")
    @PreAuthorize("hasAuthority('order:update')")
    public ResponseEntity<java.util.Map<String, Object>> requestBill(@PathVariable Long id, Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(waiterService.requestBill(id, branchScope.requireUser(authentication)));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('order:create')")
    public ResponseEntity<OrderResponse> confirm(@PathVariable Long id, Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(orderService.confirmDraft(id, idempotencyKey, branchScope.requireUser(authentication)));
    }

    @PostMapping("/{id}/rounds")
    @PreAuthorize("hasAuthority('order:update')")
    public ResponseEntity<CartResponse> addRound(@PathVariable Long id, @Valid @RequestBody CartItemsRequest request,
            Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.status(HttpStatus.CREATED).body(carts.addRound(id,
                request.items() == null ? List.of() : request.items(), branchScope.requireUser(authentication)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('order:view')")
    public ResponseEntity<OrderResponse> getOrderById(
            @PathVariable Long id, Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        OrderResponse order = orderService.getOrderById(id);
        requireWaiterOwnOrder(order, authentication);
        return ResponseEntity.ok(order);
    }

    @GetMapping("/{id}/items")
    @PreAuthorize("hasAuthority('order:view')")
    public ResponseEntity<List<OrderItemResponse>> getOrderItems(
            @PathVariable Long id, Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        requireWaiterOwnOrder(orderService.getOrderById(id), authentication);
        return ResponseEntity.ok(orderService.getOrderItems(id));
    }

    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAuthority('order:view')")
    public ResponseEntity<List<java.util.Map<String, Object>>> timeline(@PathVariable Long id, Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        requireWaiterOwnOrder(orderService.getOrderById(id), authentication);
        return ResponseEntity.ok(audit.timeline(id));
    }

    @PostMapping("/{id}/accept-qr")
    @PreAuthorize("hasAuthority('order:update')")
    public ResponseEntity<OrderResponse> acceptQrOrder(@PathVariable Long id, Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(orderService.acceptQrOrder(id));
    }

    @PostMapping("/{id}/hold")
    @PreAuthorize("hasAuthority('order:park')")
    public ResponseEntity<OrderResponse> hold(@PathVariable Long id, Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(orderService.hold(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('order:cancel')")
    public ResponseEntity<OrderResponse> cancel(@PathVariable Long id, @Valid @RequestBody OrderReasonRequest request, Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(orderService.cancel(id, request.reason(), branchScope.requireUser(authentication)));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('order:void')")
    public ResponseEntity<OrderResponse> voidOrder(@PathVariable Long id, @Valid @RequestBody OrderReasonRequest request, Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(orderService.voidOrder(id, request.reason(), branchScope.requireUser(authentication)));
    }

    @PostMapping("/{id}/payment")
    @PreAuthorize("hasAuthority('payment:record')")
    public ResponseEntity<OrderResponse> recordPayment(
            @PathVariable Long id,
            @Valid @RequestBody PaymentRequest request,
            Authentication authentication) {
        branchScope.requireBranch(authentication, orderService.getOrderRestaurantId(id));
        return ResponseEntity.ok(orderService.recordPayment(id, request));
    }

    private void requireWaiterOwnOrder(OrderResponse order, Authentication authentication) {
        var user = branchScope.requireUser(authentication);
        if (com.dinemaster.role.Roles.matches(user.getRole(), "WAITER")
                && !java.util.Objects.equals(order.waiterId(), user.getId())
                && !java.util.Objects.equals(order.createdById(), user.getId()))
            throw new org.springframework.security.access.AccessDeniedException("Waiters can access only their own orders");
    }
}
