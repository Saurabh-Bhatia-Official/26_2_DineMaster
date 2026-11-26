package com.dinemaster.orders.service;

import com.dinemaster.catalog.entity.Product;
import com.dinemaster.catalog.repository.BranchProductRepository;
import com.dinemaster.catalog.repository.ProductRepository;
import com.dinemaster.auth.service.ManagerPinService;
import com.dinemaster.customer.repository.CustomerRepository;
import com.dinemaster.diningtable.repository.DiningTableRepository;
import com.dinemaster.orders.dto.CartItemMutation;
import com.dinemaster.orders.dto.CartResponse;
import com.dinemaster.orders.dto.DiscountRequest;
import com.dinemaster.orders.dto.OrderItemResponse;
import com.dinemaster.orders.entity.Order;
import com.dinemaster.orders.entity.OrderItem;
import com.dinemaster.orders.repository.OrderItemRepository;
import com.dinemaster.orders.repository.OrderRepository;
import com.dinemaster.kitchen.service.KotService;
import com.dinemaster.restaurant.repository.RestaurantRepository;
import com.dinemaster.role.Roles;
import com.dinemaster.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class CartService {
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final RestaurantRepository restaurants;
    private final ProductRepository products;
    private final BranchProductRepository branchProducts;
    private final CustomerRepository customers;
    private final DiningTableRepository tables;
    private final ManagerPinService managerPins;
    private final TotalsCalculator totals;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BigDecimal cashierLimit;
    private final BigDecimal managerLimit;
    private final OrderNumberService orderNumbers;
    private final OrderAuditService audit;
    private final KotService kots;

    public CartService(OrderRepository orders, OrderItemRepository orderItems, RestaurantRepository restaurants,
            ProductRepository products, BranchProductRepository branchProducts, CustomerRepository customers,
            DiningTableRepository tables,
            ManagerPinService managerPins, TotalsCalculator totals, JdbcTemplate jdbc,
            ObjectMapper mapper,
            OrderNumberService orderNumbers, OrderAuditService audit, KotService kots,
            @Value("${dinemaster.pos.discount.cashier-limit-percent:10}") BigDecimal cashierLimit,
            @Value("${dinemaster.pos.discount.manager-limit-percent:10}") BigDecimal managerLimit) {
        this.orders = orders; this.orderItems = orderItems; this.restaurants = restaurants;
        this.products = products; this.branchProducts = branchProducts; this.customers = customers;
        this.tables = tables;
        this.managerPins = managerPins; this.totals = totals; this.jdbc = jdbc; this.mapper = mapper;
        this.orderNumbers = orderNumbers; this.audit = audit;
        this.kots = kots;
        this.cashierLimit = cashierLimit; this.managerLimit = managerLimit;
    }

    @Transactional
    public CartResponse createDraft(Long branchId, String type, Long tableId, Long customerId,
            Integer guestCount, String note, User actor, List<CartItemMutation> requested, String idempotencyKey) {
        var branch = restaurants.findById(branchId).orElseThrow(() -> notFound("Branch not found"));
        if (!Objects.equals(branch.getTenantId(), actor.getTenantId())) throw notFound("Branch not found");
        if ("Table".equalsIgnoreCase(type == null ? "" : type.trim()) && tableId == null)
            throw badRequest("Select a table for a Table order");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64)
            throw badRequest("A valid Idempotency-Key of at most 64 characters is required");
        String requestHash = fingerprint(branchId, type, tableId, customerId, guestCount, note, requested);
        var previous = orders.findByTenantIdAndIdempotencyKey(actor.getTenantId(), idempotencyKey);
        if (previous.isPresent()) {
            if (!Objects.equals(previous.get().getIdempotencyHash(), requestHash))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key was reused with a different cart");
            return response(previous.get());
        }
        Order order = new Order(); order.setRestaurant(branch); order.setCreatedBy(actor);
        order.setTenantId(branch.getTenantId()); order.setOrderNo(orderNumbers.next(branch));
        order.setIdempotencyKey(idempotencyKey); order.setIdempotencyHash(requestHash);
        order.setChannel("POS"); order.setStatus("DRAFT"); order.setPaymentStatus("Unpaid");
        order.setType(normalizeType(type)); order.setNotes(note); order.setGuestCount(guestCount);
        if (customerId != null) {
            var customer = customers.findById(customerId).filter(c -> Objects.equals(c.getTenantId(), actor.getTenantId()))
                    .orElseThrow(() -> notFound("Customer not found"));
            order.setCustomer(customer); order.setCustomerName(customer.getName()); order.setCustomerPhone(customer.getPhone());
        }
        if (tableId != null) {
            var table = tables.findForUpdateById(tableId).orElseThrow(() -> notFound("Table not found"));
            if (table.getRestaurant() == null || !branchId.equals(table.getRestaurant().getId()))
                throw badRequest("Table does not belong to this branch");
            if (!"Available".equalsIgnoreCase(table.getStatus()))
                throw conflict("Table is not available for a new cart");
            if (orders.countOpenOrdersForTable(tableId) > 0)
                throw conflict("Table already has an open order");
            order.setDiningTable(table); table.setStatus("Ordering");
            order.setWaiter(table.getWaiter() == null ? actor : table.getWaiter());
            order.setOrderTaker(actor.getName());
        }
        order = orders.saveAndFlush(order);
        replaceItems(order.getId(), requested == null ? List.of() : requested);
        audit.transition(order.getId(), "ORDER", order.getId(), null, "DRAFT", actor.getId(), null, null, null);
        audit.event(order.getId(), "order.created", java.util.Map.of("orderNo", order.getOrderNo(), "status", "DRAFT"));
        return response(order);
    }

    @Transactional
    public CartResponse replaceItems(Long orderId, List<CartItemMutation> requested) {
        Order order = editableCart(orderId);
        List<OrderItem> existing = orderItems.findByOrder_Id(orderId);
        orderItemRepositoryDelete(existing);
        List<OrderItem> merged = new ArrayList<>();
        for (CartItemMutation mutation : requested == null ? List.<CartItemMutation>of() : requested) {
            OrderItem same = merged.stream().filter(i -> i.getProduct().getId().equals(mutation.productId())
                    && Objects.equals(i.getNotes(), normalizeNotes(mutation.notes()))).findFirst().orElse(null);
            if (same != null) same.setQuantity(addQuantity(same.getQuantity(), mutation.quantity()));
            else merged.add(newLine(order, mutation));
        }
        if (merged.size() > 100) throw badRequest("A cart can contain at most 100 distinct lines");
        orderItems.saveAll(merged);
        recalculate(order, merged);
        orders.save(order);
        return response(order);
    }

    @Transactional
    public CartResponse addItem(Long orderId, CartItemMutation mutation) {
        Order order = editableCart(orderId);
        List<OrderItem> lines = orderItems.findByOrder_Id(orderId);
        String notes = normalizeNotes(mutation.notes());
        OrderItem same = lines.stream().filter(i -> i.getProduct().getId().equals(mutation.productId())
                && Objects.equals(i.getNotes(), notes)).findFirst().orElse(null);
        if (same != null) same.setQuantity(addQuantity(same.getQuantity(), mutation.quantity()));
        else {
            if (lines.size() >= 100) throw badRequest("A cart can contain at most 100 distinct lines");
            lines.add(newLine(order, mutation));
        }
        orderItems.saveAll(lines); recalculate(order, lines); orders.save(order);
        return response(order);
    }

    @Transactional
    public CartResponse addRound(Long orderId, List<CartItemMutation> requested, User actor) {
        Order order = orders.findForUpdateById(orderId).orElseThrow(() -> notFound("Order not found"));
        if (!"CONFIRMED".equalsIgnoreCase(order.getStatus())) throw conflict("Additional rounds require a confirmed order");
        if (requested == null || requested.isEmpty()) throw badRequest("At least one item is required for a new round");
        if (requested.size() > 100) throw badRequest("A round can contain at most 100 distinct lines");
        List<OrderItem> added = requested.stream().map(item -> newLine(order, item)).toList();
        orderItems.saveAllAndFlush(added);
        List<OrderItem> all = orderItems.findByOrder_Id(orderId);
        recalculate(order, all);
        kots.generate(orderId);
        orders.save(order);
        audit.event(orderId, "order.round_added", java.util.Map.of("itemCount", added.size(), "actorId", actor.getId()));
        return response(order);
    }

    @Transactional
    public CartResponse applyDiscount(Long orderId, DiscountRequest request, User actor) {
        Order order = editableCart(orderId);
        String type = request.type().trim().toUpperCase();
        if (!type.equals("FLAT") && !type.equals("PERCENT")) throw badRequest("Discount type must be FLAT or PERCENT");
        if (type.equals("PERCENT") && request.value().compareTo(new BigDecimal("100")) >= 0)
            throw badRequest("A 100 percent discount is not allowed");
        List<OrderItem> lines = orderItems.findByOrder_Id(orderId);
        BigDecimal subtotal = lineSubtotal(lines);
        BigDecimal amount = type.equals("PERCENT")
                ? subtotal.multiply(request.value()).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP)
                : request.value();
        if (subtotal.signum() <= 0 || amount.compareTo(subtotal) >= 0)
            throw badRequest("Discount must be below the eligible subtotal");
        BigDecimal percent = amount.multiply(new BigDecimal("100")).divide(subtotal, 4, java.math.RoundingMode.HALF_UP);
        BigDecimal limit = Roles.matches(actor.getRole(), "CASHIER") ? cashierLimit : managerLimit;
        User approver = null;
        if (percent.compareTo(limit) > 0) {
            try { approver = managerPins.verify(request.managerUsername(), request.managerPin(),
                    order.getRestaurant().getTenantId(), order.getRestaurant().getId()); }
            catch (ManagerPinService.InvalidManagerPinException ex) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Manager PIN approval failed");
            }
        }
        order.setDiscountType(type); order.setDiscountValue(request.value()); order.setDiscountRemark(request.remark().trim());
        recalculate(order, lines); orders.save(order);
        jdbc.update("""
                INSERT INTO pos_discount_audit(order_id, cashier_id, approver_id, discount_type, requested_value, discount_amount, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, orderId, actor.getId(), approver == null ? null : approver.getId(), type,
                request.value(), order.getDiscountAmount(), request.remark().trim());
        return response(order);
    }

    @Transactional
    public CartResponse park(Long orderId) { Order o = editable(orderId); o.setParked(true); orders.save(o); return response(o); }

    @Transactional
    public CartResponse clearDiscount(Long orderId) {
        Order order = editableCart(orderId);
        order.setDiscountType("FLAT"); order.setDiscountValue(BigDecimal.ZERO); order.setDiscountRemark(null);
        recalculate(order, orderItems.findByOrder_Id(orderId));
        return response(orders.save(order));
    }

    @Transactional
    public CartResponse reopen(Long orderId) {
        Order o = editable(orderId);
        if (!o.isParked()) throw conflict("Cart is not parked");
        o.setParked(false); orders.save(o); return response(o);
    }

    @Transactional
    public CartResponse attachCustomer(Long orderId, Long customerId, User actor) {
        Order order = editableCart(orderId);
        if (customerId == null) {
            order.setCustomer(null); order.setCustomerName(null); order.setCustomerPhone(null);
        } else {
            var customer = customers.findById(customerId).filter(c -> Objects.equals(c.getTenantId(), actor.getTenantId()))
                    .orElseThrow(() -> notFound("Customer not found"));
            order.setCustomer(customer); order.setCustomerName(customer.getName()); order.setCustomerPhone(customer.getPhone());
        }
        return response(orders.save(order));
    }

    @Transactional(readOnly = true)
    public List<CartResponse> parked(Long branchId) {
        return orders.findByRestaurant_IdAndStatusAndParkedTrueOrderByOrderTimeDesc(branchId, "DRAFT")
                .stream().map(this::response).toList();
    }

    private OrderItem newLine(Order order, CartItemMutation mutation) {
        Product product = products.findByIdAndRestaurant_Id(mutation.productId(), order.getRestaurant().getId())
                .filter(p -> p.getDeletedAt() == null && p.isAvailable())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Product is unavailable"));
        var branch = branchProducts.findByBranchIdAndProductId(order.getRestaurant().getId(), product.getId()).orElse(null);
        if (branch != null && !branch.isAvailable()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Product is unavailable");
        OrderItem item = new OrderItem(); item.setOrder(order); item.setProduct(product);
        item.setStation(product.getStation());
        item.setProductNameSnapshot(product.getName()); item.setQuantity(mutation.quantity()); item.setNotes(normalizeNotes(mutation.notes()));
        item.setStatus("NEW"); item.setPrice(branch != null && branch.getPriceOverride() != null ? branch.getPriceOverride() : product.getPrice());
        item.setTaxRateSnapshot(product.getGstPercentage());
        return item;
    }

    private Order editable(Long orderId) {
        Order order = orders.findForUpdateById(orderId).orElseThrow(() -> notFound("Order not found"));
        if (!"DRAFT".equalsIgnoreCase(order.getStatus())) throw conflict("Only draft carts can be changed");
        return order;
    }
    private Order editableCart(Long orderId) {
        Order order = editable(orderId);
        if (order.isParked()) throw conflict("Reopen the parked cart before editing it");
        return order;
    }
    private void recalculate(Order order, List<OrderItem> lines) {
        try { totals.recalculate(order, lines); }
        catch (IllegalArgumentException ex) { throw badRequest(ex.getMessage()); }
    }
    private BigDecimal lineSubtotal(List<OrderItem> lines) {
        return lines.stream().filter(i -> !"CANCELLED".equalsIgnoreCase(i.getStatus()))
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private int addQuantity(int current, int addition) {
        try { return Math.addExact(current, addition); }
        catch (ArithmeticException ex) { throw badRequest("Cart line quantity exceeds the supported limit"); }
    }
    private CartResponse response(Order order) {
        List<OrderItemResponse> lines = orderItems.findByOrder_Id(order.getId()).stream().map(OrderItemResponse::from).toList();
        return new CartResponse(order.getId(), order.getRestaurant().getId(),
                order.getCustomer() == null ? null : order.getCustomer().getId(),
                order.getDiningTable() == null ? null : order.getDiningTable().getId(), order.getType(), order.getStatus(),
                order.isParked(), order.getSubtotal(), order.getDiscountAmount(), order.getDiscountType(),
                order.getDiscountRemark(), order.getTaxAmount(), order.getRoundOff(), order.getTotalAmount(),
                order.getOrderNo(), order.getVersion(), lines);
    }
    private void replaceItemsDelete(List<OrderItem> items) { orderItems.deleteAll(items); orderItems.flush(); }
    private void orderItemRepositoryDelete(List<OrderItem> items) { replaceItemsDelete(items); }
    private String normalizeType(String type) {
        String value = type == null || type.isBlank() ? "Dine In" : type.trim();
        if (value.equalsIgnoreCase("Dine-In")) value = "Dine In";
        String normalized = value;
        if (!List.of("Dine In", "Table", "Takeaway", "Delivery", "Online", "Walk-in").stream().anyMatch(v -> v.equalsIgnoreCase(normalized)))
            throw badRequest("Unsupported POS order type");
        return value.equalsIgnoreCase("Table") ? "Dine In" : value;
    }
    private String normalizeNotes(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String fingerprint(Long branchId, String type, Long tableId, Long customerId, Integer guestCount,
            String note, List<CartItemMutation> items) {
        try {
            byte[] payload = mapper.writeValueAsBytes(new Object[]{branchId, type, tableId, customerId,
                    guestCount, note, items == null ? List.of() : items});
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception ex) { throw new IllegalStateException("Could not fingerprint cart request", ex); }
    }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
