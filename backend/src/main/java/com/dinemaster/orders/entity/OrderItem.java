package com.dinemaster.orders.entity;




import com.dinemaster.catalog.entity.Product;
import com.dinemaster.kitchen.entity.Kot;
import com.dinemaster.user.entity.User;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_name_snapshot", nullable = false, length = 150)
    private String productNameSnapshot;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "tax_rate_snapshot", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRateSnapshot = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false, length = 30)
    private String status = "Pending";

    @Column(length = 30)
    private String station;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;
    @Column(name = "ready_at")
    private OffsetDateTime readyAt;
    @Column(name = "delay_reason", columnDefinition = "TEXT")
    private String delayReason;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chef_id")
    private User chef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kot_id")
    private Kot kot;

    public OrderItem() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }
    public String getProductNameSnapshot() { return productNameSnapshot; }
    public void setProductNameSnapshot(String value) { productNameSnapshot = value; }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }
    public BigDecimal getTaxRateSnapshot() { return taxRateSnapshot; }
    public void setTaxRateSnapshot(BigDecimal value) { taxRateSnapshot = value; }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getStatus() {
        return status;
    }
    public String getStation() { return station; }
    public void setStation(String value) { station = value; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String value) { cancelReason = value; }
    public int getVersion() { return version; }
    public void setVersion(int value) { version = value; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(OffsetDateTime value) { acceptedAt = value; }
    public OffsetDateTime getReadyAt() { return readyAt; }
    public void setReadyAt(OffsetDateTime value) { readyAt = value; }
    public String getDelayReason() { return delayReason; }
    public void setDelayReason(String value) { delayReason = value; }
    public User getChef() { return chef; }
    public void setChef(User value) { chef = value; }

    public void setStatus(String status) {
        this.status = status;
    }

    public Kot getKot() {
        return kot;
    }

    public void setKot(Kot kot) {
        this.kot = kot;
    }
}
