package com.dinemaster.recipe.repository;
import com.dinemaster.recipe.entity.RecipeDeductionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface RecipeDeductionJobRepository extends JpaRepository<RecipeDeductionJob, Long> {
    List<RecipeDeductionJob> findTop20ByStatusOrderByCreatedAtAsc(String status);
}
