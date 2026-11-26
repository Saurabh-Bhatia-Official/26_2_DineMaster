package com.dinemaster.catalog.repository;
import com.dinemaster.catalog.entity.BranchProduct;
import com.dinemaster.catalog.entity.BranchProductId;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface BranchProductRepository extends JpaRepository<BranchProduct, BranchProductId> {
    List<BranchProduct> findByBranchId(Long branchId);
    Optional<BranchProduct> findByBranchIdAndProductId(Long branchId, Long productId);
}
