package com.dinemaster.kitchen.repository;
import com.dinemaster.kitchen.entity.PrintJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PrintJobRepository extends JpaRepository<PrintJob, Long> {
    List<PrintJob> findByBranchIdAndStatusOrderByCreatedAtAsc(Long branchId, String status);
}
