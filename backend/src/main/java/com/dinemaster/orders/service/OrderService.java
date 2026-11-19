package com.dinemaster.orders.service;




import com.dinemaster.catalog.entity.Product;
import com.dinemaster.catalog.repository.ProductRepository;
import com.dinemaster.customer.entity.Customer;
import com.dinemaster.customer.repository.CustomerRepository;
import com.dinemaster.diningtable.entity.DiningTable;
import com.dinemaster.diningtable.repository.DiningTableRepository;
import com.dinemaster.orders.dto.CreateOrderRequest;
import com.dinemaster.orders.dto.OrderItemRequest;
import com.dinemaster.orders.dto.OrderItemResponse;
import com.dinemaster.orders.dto.OrderResponse;
import com.dinemaster.orders.dto.PaymentRequest;
import com.dinemaster.orders.entity.Order;
import com.dinemaster.orders.entity.OrderItem;
import com.dinemaster.orders.repository.OrderItemRepository;
import com.dinemaster.orders.repository.OrderRepository;
import com.dinemaster.restaurant.entity.Restaurant;
import com.dinemaster.restaurant.repository.RestaurantRepository;
import com.dinemaster.user.entity.User;
import com.dinemaster.user.repository.UserRepository;
import com.dinemaster.user.repository.UserBranchRepository;
import com.dinemaster.kitchen.service.KotService;
import com.dinemaster.catalog.repository.BranchProductRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class OrderService {

        private final OrderRepository orderRepository;
        private final OrderItemRepository orderItemRepository;
        private final ProductRepository productRepository;
        private final RestaurantRepository restaurantRepository;
        private final DiningTableRepository diningTableRepository;
        private final CustomerRepository customerRepository;
        private final UserRepository userRepository;
        private final UserBranchRepository userBranches;
        private final KotService kotService;
        private final BranchProductRepository branchProducts;
        private final JdbcTemplate jdbc;
        private final OrderStateMachine stateMachine;
        private final OrderAuditService audit;

        public OrderService(
                        OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository,
                        ProductRepository productRepository,
                        RestaurantRepository restaurantRepository,
                        DiningTableRepository diningTableRepository,
                        CustomerRepository customerRepository,
                        UserRepository userRepository,
                        UserBranchRepository userBranches,
                        KotService kotService,
                        BranchProductRepository branchProducts,
                        JdbcTemplate jdbc, OrderStateMachine stateMachine, OrderAuditService audit) {
                this.orderRepository = orderRepository;
                this.orderItemRepository = orderItemRepository;
                this.productRepository = productRepository;
                this.restaurantRepository = restaurantRepository;
                this.diningTableRepository = diningTableRepository;
                this.customerRepository = customerRepository;
                this.userRepository = userRepository;
                this.userBranches = userBranches;
                this.kotService = kotService;
                this.branchProducts = branchProducts;
                this.jdbc = jdbc;
                this.stateMachine = stateMachine;
                this.audit = audit;
        }

        @Transactional
        public OrderResponse createOrder(CreateOrderRequest request) {

                Restaurant restaurant = restaurantRepository
                                .findById(request.getRestaurantId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Restaurant not found"));

                Order order = new Order();
                order.setRestaurant(restaurant);
                order.setStatus("Pending");
                order.setType(request.getType() == null
                                || request.getType().isBlank()
                                                ? "Dine In"
                                                : request.getType().trim());
                order.setNotes(request.getNotes());
                order.setGuestCount(request.getGuestCount());

                if (request.getTableId() != null) {
                        DiningTable table = diningTableRepository
                                        .findForUpdateById(request.getTableId())
                                        .orElseThrow(() -> new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND, "Table not found"));

                        if (table.getRestaurant() == null
                                        || !restaurant.getId().equals(table.getRestaurant().getId())) {
                                throw new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST,
                                                "Table does not belong to this restaurant");
                        }

                        if (!"Available".equalsIgnoreCase(table.getStatus())) {
                                throw new ResponseStatusException(HttpStatus.CONFLICT,
                                                "Table is not available for a new order");
                        }
                        if (orderRepository.countOpenOrdersForTable(table.getId()) > 0) {
                                throw new ResponseStatusException(HttpStatus.CONFLICT,
                                                "Table already has an open order");
                        }

                        order.setDiningTable(table);
                        table.setStatus("Ordering");
                }

                if (request.getCustomerId() != null) {
                        Customer customer = customerRepository
                                        .findById(request.getCustomerId())
                                        .orElseThrow(() -> new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND, "Customer not found"));
                        if (!restaurant.getTenantId().equals(customer.getTenantId())) {
                                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
                        }
                        order.setCustomer(customer);
                        order.setCustomerName(customer.getName());
                        order.setCustomerPhone(customer.getPhone());
                }

                if (request.getWaiterId() != null) {
                        User waiter = userRepository
                                        .findById(request.getWaiterId())
                                        .orElseThrow(() -> new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND, "Waiter not found"));
                        if (!waiter.isActive() || !restaurant.getTenantId().equals(waiter.getTenantId())
                                        || !userBranches.isAssigned(waiter.getId(), restaurant.getId())) {
                                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Waiter not found");
                        }
                        order.setWaiter(waiter);
                        order.setOrderTaker(waiter.getName());
                        if (order.getDiningTable() != null) order.getDiningTable().setWaiter(waiter);
                }

                BigDecimal subtotal = BigDecimal.ZERO;
                List<OrderItem> items = new ArrayList<>();

                for (OrderItemRequest itemRequest : request.getItems()) {
                        Product product = productRepository
                                        .findById(itemRequest.getProductId())
                                        .orElseThrow(() -> new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "Product not found: " + itemRequest.getProductId()));

                        if (!restaurant.getId().equals(product.getRestaurantId())) {
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                "Every product must belong to the order's restaurant");
                        }

                        var branchProduct = branchProducts.findByBranchIdAndProductId(restaurant.getId(), product.getId()).orElse(null);
                        if (!product.isAvailable() || branchProduct != null && !branchProduct.isAvailable()) {
                                throw new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST,
                                                "Product is unavailable: " + product.getName());
                        }

                        BigDecimal effectivePrice = branchProduct != null && branchProduct.getPriceOverride() != null
                                ? branchProduct.getPriceOverride() : product.getPrice();
                        BigDecimal lineTotal = effectivePrice.multiply(
                                        BigDecimal.valueOf(itemRequest.getQuantity()));

                        subtotal = subtotal.add(lineTotal);

                        OrderItem item = new OrderItem();
                        item.setOrder(order);
                        item.setProduct(product);
                        item.setProductNameSnapshot(product.getName());
                        item.setQuantity(itemRequest.getQuantity());
                        item.setPrice(effectivePrice);
                        item.setNotes(itemRequest.getNotes());
                        item.setStatus("Pending");

                        items.add(item);
                }

                BigDecimal discount = request.getDiscountAmount() == null
                                ? BigDecimal.ZERO
                                : request.getDiscountAmount();

                if (discount.compareTo(BigDecimal.ZERO) < 0
                                || discount.compareTo(subtotal) > 0) {
                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Discount must be between zero and the subtotal");
                }

                order.setDiscountAmount(discount);
                order.setTotalAmount(subtotal.subtract(discount));

                Order savedOrder = orderRepository.saveAndFlush(order);

                for (OrderItem item : items) {
                        item.setOrder(savedOrder);
                }

                orderItemRepository.saveAll(items);

                return OrderResponse.from(savedOrder);
        }

        @Transactional(readOnly = true)
        public List<OrderResponse> getOrdersByRestaurant(Long restaurantId) {
                if (!restaurantRepository.existsById(restaurantId)) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Restaurant not found");
                }

                return orderRepository
                                .findByRestaurant_IdOrderByOrderTimeDesc(restaurantId)
                                .stream()
                                .map(OrderResponse::from)
                                .toList();
        }

        @Transactional(readOnly = true)
        public OrderResponse getOrderById(Long orderId) {
                Order order = orderRepository.findById(orderId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Order not found"));
                return OrderResponse.from(order);
        }

        @Transactional(readOnly = true)
        public Long getOrderRestaurantId(Long orderId) {
                return findOrder(orderId).getRestaurant().getId();
        }

        @Transactional(readOnly = true)
        public List<OrderItemResponse> getOrderItems(Long orderId) {
                if (!orderRepository.existsById(orderId)) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Order not found");
                }

                return orderItemRepository.findByOrder_Id(orderId)
                                .stream()
                                .map(OrderItemResponse::from)
                                .toList();
        }

        @Transactional
        public OrderResponse confirmDraft(Long orderId, String idempotencyKey, User actor) {
                Order order = orderRepository.findForUpdateById(orderId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
                if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64)
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid Idempotency-Key is required");
                if ("CONFIRMED".equalsIgnoreCase(order.getStatus())) {
                        if (idempotencyKey.equals(order.getConfirmIdempotencyKey())) return OrderResponse.from(order);
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Order was already confirmed with another request key");
                }
                if (!"DRAFT".equalsIgnoreCase(order.getStatus()) || "QR".equalsIgnoreCase(order.getChannel()))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a POS draft can be confirmed here");
                if (order.isParked()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Reopen the cart before confirming it");
                List<OrderItem> items = orderItemRepository.findByOrder_Id(orderId);
                if (items.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot confirm an empty cart");
                for (OrderItem item : items) {
                        Product current = productRepository.findByIdAndRestaurant_Id(item.getProduct().getId(), order.getRestaurant().getId())
                                        .filter(p -> p.getDeletedAt() == null && p.isAvailable())
                                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "A cart product is no longer available"));
                        var branch = branchProducts.findByBranchIdAndProductId(order.getRestaurant().getId(), current.getId()).orElse(null);
                        BigDecimal currentPrice = branch != null && branch.getPriceOverride() != null ? branch.getPriceOverride() : current.getPrice();
                        if (branch != null && !branch.isAvailable() || currentPrice.compareTo(item.getPrice()) != 0)
                                throw new ResponseStatusException(HttpStatus.CONFLICT, "Menu availability or price changed; refresh the cart before confirming");
                }
                stateMachine.requireTransition(order.getStatus(), "CONFIRMED");
                order.setStatus("CONFIRMED"); order.setConfirmedAt(java.time.OffsetDateTime.now()); order.setParked(false);
                order.setConfirmIdempotencyKey(idempotencyKey);
                try {
                        order.setConfirmIdempotencyHash(java.util.HexFormat.of().formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                        .digest((orderId + ":" + idempotencyKey).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
                if (order.getDiningTable() != null) order.getDiningTable().setStatus("Ordering");
                kotService.generate(orderId);
                audit.transition(orderId, "ORDER", orderId, "DRAFT", "CONFIRMED", actor.getId(), null, null, null);
                audit.event(orderId, "order.confirmed", java.util.Map.of("status", "CONFIRMED"));
                return OrderResponse.from(orderRepository.save(order));
        }

        @Transactional
        public OrderResponse hold(Long orderId) {
                Order order = findOrder(orderId);
                if (!"DRAFT".equalsIgnoreCase(order.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only draft orders can be parked");
                order.setParked(true);
                return OrderResponse.from(orderRepository.save(order));
        }

        @Transactional
        public OrderResponse cancel(Long orderId, String reason, User actor) {
                Order order = orderRepository.findForUpdateById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
                if (reason == null || reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cancellation reason is required");
                if ("DRAFT".equalsIgnoreCase(order.getStatus()) && !java.util.Objects.equals(order.getCreatedBy() == null ? null : order.getCreatedBy().getId(), actor.getId())
                        && !com.dinemaster.role.Roles.matches(actor.getRole(), "MANAGER")
                        && !com.dinemaster.role.Roles.matches(actor.getRole(), "OWNER"))
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the order creator or a manager can cancel a draft");
                stateMachine.requireTransition(order.getStatus(), "CANCELLED");
                List<OrderItem> lines = orderItemRepository.findByOrder_Id(orderId);
                if ("CONFIRMED".equalsIgnoreCase(order.getStatus()) && lines.stream().anyMatch(i -> List.of("COOKING", "READY", "SERVED").contains(i.getStatus().toUpperCase())))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Order cannot be cancelled after kitchen preparation has started");
                if ("CONFIRMED".equalsIgnoreCase(order.getStatus())) kotService.createCancellationNotices(orderId, lines, reason.trim(), actor);
                String oldStatus = order.getStatus(); order.setStatus("CANCELLED"); order.setCancelReason(reason.trim());
                for (OrderItem line : lines) { String from = line.getStatus(); line.setStatus("CANCELLED"); line.setCancelReason(reason.trim()); audit.transition(orderId, "ITEM", line.getId(), from, "CANCELLED", actor.getId(), null, reason.trim(), null); }
                if (order.getDiningTable() != null) order.getDiningTable().setStatus("Cleaning");
                audit.transition(orderId, "ORDER", orderId, oldStatus, "CANCELLED", actor.getId(), null, reason.trim(), null);
                audit.event(orderId, "order.cancelled", java.util.Map.of("reason", reason.trim()));
                return OrderResponse.from(orderRepository.save(order));
        }

        @Transactional
        public OrderResponse voidOrder(Long orderId, String reason, User actor) {
                Order order = orderRepository.findForUpdateById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
                if (reason == null || reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Void reason is required");
                if (!com.dinemaster.role.Roles.matches(actor.getRole(), "MANAGER") && !com.dinemaster.role.Roles.matches(actor.getRole(), "OWNER"))
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Manager approval is required to void an order");
                if (!"CONFIRMED".equalsIgnoreCase(order.getStatus()))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Paid order voids must be processed through the refund workflow");
                stateMachine.requireTransition(order.getStatus(), "VOIDED");
                String from = order.getStatus(); order.setStatus("VOIDED"); order.setVoidReason(reason.trim());
                audit.transition(orderId, "ORDER", orderId, from, "VOIDED", actor.getId(), actor.getId(), reason.trim(), null);
                audit.event(orderId, "order.voided", java.util.Map.of("reason", reason.trim()));
                return OrderResponse.from(orderRepository.save(order));
        }

        @Transactional
        public OrderResponse recordPayment(Long orderId, PaymentRequest request) {
                String paymentMethod = request.paymentMethod().trim();
                if (paymentMethod.length() > 50) {
                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "Payment method cannot exceed 50 characters");
                }
                Order order = findOrder(orderId);
                if ("Cancelled".equalsIgnoreCase(order.getStatus())) {
                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "A cancelled order cannot be paid");
                }
                if (!"BILLED".equalsIgnoreCase(order.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order must be billed before payment");
                stateMachine.requireTransition(order.getStatus(), "PAID");
                order.setPaymentStatus("Paid");
                order.setPaymentMethod(paymentMethod);
                String priorStatus = order.getStatus(); order.setStatus("PAID");
                Order saved = orderRepository.save(order);
                audit.transition(orderId, "ORDER", orderId, priorStatus, "PAID", null, null, null, null);
                audit.event(orderId, "order.paid", java.util.Map.of("paymentMethod", paymentMethod));
                jdbc.update("INSERT INTO recipe_deduction_jobs(order_id) VALUES (?) ON CONFLICT (order_id) DO NOTHING", orderId);
                jdbc.update("""
                        INSERT INTO order_recipe_snapshots(order_item_id, recipe_id)
                        SELECT oi.id, r.id FROM order_items oi
                        LEFT JOIN recipes r ON r.product_id = oi.product_id AND r.is_active = TRUE
                        WHERE oi.order_id = ? ON CONFLICT (order_item_id) DO NOTHING
                        """, orderId);
                return OrderResponse.from(saved);
        }

        private Order findOrder(Long orderId) {
                return orderRepository.findById(orderId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Order not found"));
        }

        @Transactional
        public OrderResponse acceptQrOrder(Long orderId) {
                Order order = findOrder(orderId);
                if (!"QR".equalsIgnoreCase(order.getChannel()) || !"DRAFT".equalsIgnoreCase(order.getStatus())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a QR draft order can be accepted");
                }
                order.setStatus("CONFIRMED"); order.setConfirmedAt(java.time.OffsetDateTime.now());
                kotService.generate(orderId);
                audit.transition(orderId, "ORDER", orderId, "DRAFT", "CONFIRMED", null, null, "QR order accepted", null);
                audit.event(orderId, "order.confirmed", java.util.Map.of("source", "QR"));
                return OrderResponse.from(orderRepository.save(order));
        }
}
