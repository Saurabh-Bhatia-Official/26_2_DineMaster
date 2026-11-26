package com.dinemaster.catalog.repository;
import com.dinemaster.catalog.entity.TaxSlab;
import org.springframework.data.jpa.repository.JpaRepository;
import java.math.BigDecimal;
import java.util.Optional;
public interface TaxSlabRepository extends JpaRepository<TaxSlab, Long> {
    Optional<TaxSlab> findByTenantIdAndRatePercentAndActiveTrue(Long tenantId, BigDecimal ratePercent);
}
