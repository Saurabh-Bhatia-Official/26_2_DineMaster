package com.dinemaster.recipe.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
public record RecipeRequest(
        String steps,
        @Positive Integer prepMinutes,
        @Positive Integer cookMinutes,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal portion,
        @Size(max = 30) String difficulty,
        @Size(max = 2000) String videoUrl,
        List<@Valid Ingredient> ingredients) {
    public record Ingredient(@NotNull @Positive Long ingredientId,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity,
            @NotBlank @Size(max = 30) String unit) { }
}
