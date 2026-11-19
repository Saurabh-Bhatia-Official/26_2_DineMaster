package com.dinemaster.catalog.entity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
@Entity
@Table(name = "branch_products")
@IdClass(BranchProductId.class)
public class BranchProduct {
    @Id @Column(name = "branch_id") private Long branchId;
    @Id @Column(name = "product_id") private Long productId;
    @Column(name = "is_available", nullable = false) private boolean available = true;
    @Column(name = "price_override", precision = 12, scale = 2) private BigDecimal priceOverride;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
    @PrePersist @PreUpdate void touch() { updatedAt = OffsetDateTime.now(); }
    public Long getBranchId() { return branchId; }
    public void setBranchId(Long value) { branchId = value; }
    public Long getProductId() { return productId; }
    public void setProductId(Long value) { productId = value; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean value) { available = value; }
    public BigDecimal getPriceOverride() { return priceOverride; }
    public void setPriceOverride(BigDecimal value) { priceOverride = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
