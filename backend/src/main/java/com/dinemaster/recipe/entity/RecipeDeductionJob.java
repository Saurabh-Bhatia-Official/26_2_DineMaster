package com.dinemaster.recipe.entity;
import com.dinemaster.orders.entity.Order;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
@Entity
@Table(name = "recipe_deduction_jobs")
public class RecipeDeductionJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id", nullable = false) private Order order;
    @Column(nullable = false, length = 20) private String status = "QUEUED";
    @Column(nullable = false) private Integer attempts = 0;
    @Column(columnDefinition = "TEXT") private String error;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
    @PrePersist void onCreate() { OffsetDateTime now = OffsetDateTime.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = OffsetDateTime.now(); }
    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public void setOrder(Order value) { order = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer value) { attempts = value; }
    public String getError() { return error; }
    public void setError(String value) { error = value; }
}
