package com.dinemaster.recipe.entity;

import com.dinemaster.catalog.entity.Product;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "recipes", uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "version"}))
public class Recipe {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "product_id", nullable = false) private Product product;
    @Column(nullable = false) private Integer version;
    @Column(nullable = false, columnDefinition = "TEXT") private String steps = "[]";
    @Column(name = "prep_min") private Integer prepMinutes;
    @Column(name = "cook_min") private Integer cookMinutes;
    @Column(precision = 12, scale = 3) private BigDecimal portion;
    @Column(length = 30) private String difficulty;
    @Column(name = "video_url", columnDefinition = "TEXT") private String videoUrl;
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @PrePersist void onCreate() { if (createdAt == null) createdAt = OffsetDateTime.now(); }
    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public void setProduct(Product value) { product = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version = value; }
    public String getSteps() { return steps; }
    public void setSteps(String value) { steps = value; }
    public Integer getPrepMinutes() { return prepMinutes; }
    public void setPrepMinutes(Integer value) { prepMinutes = value; }
    public Integer getCookMinutes() { return cookMinutes; }
    public void setCookMinutes(Integer value) { cookMinutes = value; }
    public BigDecimal getPortion() { return portion; }
    public void setPortion(BigDecimal value) { portion = value; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String value) { difficulty = value; }
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String value) { videoUrl = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
}
