package com.dinemaster.catalog.entity;
import jakarta.persistence.*;
import java.math.BigDecimal;
@Entity
@Table(name = "tax_slabs", uniqueConstraints = {
        @UniqueConstraint(name = "uq_tax_slabs_tenant_rate", columnNames = {"tenant_id", "rate_percent"})})
public class TaxSlab {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(nullable = false, length = 40) private String name;
    @Column(name = "rate_percent", nullable = false, precision = 5, scale = 2) private BigDecimal ratePercent;
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @Column(nullable = false) private Integer version = 1;
    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public BigDecimal getRatePercent() { return ratePercent; }
    public void setRatePercent(BigDecimal value) { ratePercent = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public Integer getVersion() { return version; }
}
