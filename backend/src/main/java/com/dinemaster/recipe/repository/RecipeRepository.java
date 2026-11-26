package com.dinemaster.recipe.repository;




import com.dinemaster.recipe.entity.Recipe;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {
    List<Recipe> findByProduct_IdOrderByVersionDesc(Long productId);
    java.util.Optional<Recipe> findFirstByProduct_IdAndActiveTrue(Long productId);
    java.util.Optional<Recipe> findFirstByProduct_IdOrderByVersionDesc(Long productId);
}
