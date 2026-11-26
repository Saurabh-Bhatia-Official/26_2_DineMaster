package com.dinemaster.recipe.repository;
import com.dinemaster.recipe.entity.RecipeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface RecipeItemRepository extends JpaRepository<RecipeItem, Long> {
    List<RecipeItem> findByRecipe_IdOrderByIdAsc(Long recipeId);
    void deleteByRecipe_Id(Long recipeId);
}
