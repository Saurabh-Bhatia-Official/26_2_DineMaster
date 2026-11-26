package com.dinemaster.orders.entity;




import com.dinemaster.customer.entity.Customer;
import com.dinemaster.diningtable.entity.DiningTable;
import com.dinemaster.restaurant.entity.Restaurant;
import com.dinemaster.user.entity.User;
import com.dinemaster.qr.entity.TableSession;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "order_no", nullable = false, length = 40)
    private String orderNo;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "void_reason", columnDefinition = "TEXT")
    private String voidReason;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_id")
    private DiningTable diningTable;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "waiter_id")
    private User waiter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id")
    private Restaurant restaurant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_session_id")
    private TableSession tableSession;

    @Column(nullable = false, length = 20)
    private String channel = "STAFF";

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "round_off", nullable = false, precision = 5, scale = 2)
    private BigDecimal roundOff = BigDecimal.ZERO;

    @Column(name = "discount_type", nullable = false, length = 12)
    private String discountType = "FLAT";

    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountValue = BigDecimal.ZERO;

    @Column(name = "discount_remark", columnDefinition = "TEXT")
    private String discountRemark;

    @Column(nullable = false)
    private boolean parked;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "idempotency_hash", length = 64)
    private String idempotencyHash;

    @Column(name = "confirm_idempotency_key", length = 64)
    private String confirmIdempotencyKey;

    @Column(name = "confirm_idempotency_hash", length = 64)
    private String confirmIdempotencyHash;

    @Column(nullable = false, length = 30)
    private String status = "Pending";

    @Column(name = "type", nullable = false, length = 30)
    private String type = "Dine In";

    @Column(name = "order_time", nullable = false)
    private OffsetDateTime orderTime;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "payment_status", nullable = false, length = 30)
    private String paymentStatus = "Unpaid";

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "customer_name", length = 150)
    private String customerName;

    @Column(name = "customer_phone", length = 30)
    private String customerPhone;

    @Column(name = "order_taker", length = 150)
    private String orderTaker;

    @Column(name = "delivered_by", length = 150)
    private String deliveredBy;

    @Column(name = "delivery_timestamp")
    private OffsetDateTime deliveryTimestamp;

    @Column(name = "guest_count")
    private Integer guestCount;

    @PrePersist
    protected void onCreate() {
        if (orderTime == null) {
            orderTime = OffsetDateTime.now();
        }
    }

    public Order() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String value) { orderNo = value; }
    public int getVersion() { return version; }
    public void setVersion(int value) { version = value; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(OffsetDateTime value) { confirmedAt = value; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime value) { completedAt = value; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String value) { voidReason = value; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String value) { cancelReason = value; }

    public DiningTable getDiningTable() { return diningTable; }
    public void setDiningTable(DiningTable diningTable) {
        this.diningTable = diningTable;
    }

    public Customer getCustomer() { return customer; }
    public void setCustomer(Customer customer) { this.customer = customer; }

    public User getWaiter() { return waiter; }
    public void setWaiter(User waiter) { this.waiter = waiter; }

    public Restaurant getRestaurant() { return restaurant; }
    public void setRestaurant(Restaurant restaurant) {
        this.restaurant = restaurant;
    }

    public TableSession getTableSession() { return tableSession; }
    public void setTableSession(TableSession tableSession) { this.tableSession = tableSession; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal value) { subtotal = value; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal value) { taxAmount = value; }
    public BigDecimal getRoundOff() { return roundOff; }
    public void setRoundOff(BigDecimal value) { roundOff = value; }
    public String getDiscountType() { return discountType; }
    public void setDiscountType(String value) { discountType = value; }
    public BigDecimal getDiscountValue() { return discountValue; }
    public void setDiscountValue(BigDecimal value) { discountValue = value; }
    public String getDiscountRemark() { return discountRemark; }
    public void setDiscountRemark(String value) { discountRemark = value; }
    public boolean isParked() { return parked; }
    public void setParked(boolean value) { parked = value; }
    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User value) { createdBy = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getIdempotencyHash() { return idempotencyHash; }
    public void setIdempotencyHash(String value) { idempotencyHash = value; }
    public String getConfirmIdempotencyKey() { return confirmIdempotencyKey; }
    public void setConfirmIdempotencyKey(String value) { confirmIdempotencyKey = value; }
    public String getConfirmIdempotencyHash() { return confirmIdempotencyHash; }
    public void setConfirmIdempotencyHash(String value) { confirmIdempotencyHash = value; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public OffsetDateTime getOrderTime() { return orderTime; }
    public void setOrderTime(OffsetDateTime orderTime) {
        this.orderTime = orderTime;
    }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public String getOrderTaker() { return orderTaker; }
    public void setOrderTaker(String orderTaker) {
        this.orderTaker = orderTaker;
    }

    public String getDeliveredBy() { return deliveredBy; }
    public void setDeliveredBy(String deliveredBy) {
        this.deliveredBy = deliveredBy;
    }

    public OffsetDateTime getDeliveryTimestamp() { return deliveryTimestamp; }
    public void setDeliveryTimestamp(OffsetDateTime deliveryTimestamp) {
        this.deliveryTimestamp = deliveryTimestamp;
    }

    public Integer getGuestCount() { return guestCount; }
    public void setGuestCount(Integer guestCount) {
        this.guestCount = guestCount;
    }
}
