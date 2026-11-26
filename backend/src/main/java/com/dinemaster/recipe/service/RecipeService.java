package com.dinemaster.recipe.service;

import com.dinemaster.catalog.entity.Product;
import com.dinemaster.catalog.repository.ProductRepository;
import com.dinemaster.inventory.entity.InventoryItem;
import com.dinemaster.inventory.repository.InventoryRepository;
import com.dinemaster.recipe.dto.RecipeRequest;
import com.dinemaster.recipe.entity.Recipe;
import com.dinemaster.recipe.entity.RecipeItem;
import com.dinemaster.recipe.repository.RecipeItemRepository;
import com.dinemaster.recipe.repository.RecipeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;

@Service
public class RecipeService {
    private final RecipeRepository recipes;
    private final RecipeItemRepository items;
    private final ProductRepository products;
    private final InventoryRepository inventory;
    private final UnitConversionService units;
    private final ObjectMapper mapper;
    public RecipeService(RecipeRepository recipes, RecipeItemRepository items, ProductRepository products,
            InventoryRepository inventory, UnitConversionService units, ObjectMapper mapper) {
        this.recipes = recipes; this.items = items; this.products = products; this.inventory = inventory;
        this.units = units; this.mapper = mapper;
    }
    @Transactional
    public Map<String, Object> save(Long productId, Long tenantId, RecipeRequest request) {
        Product product = products.findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        String steps = request.steps() == null || request.steps().isBlank() ? "[]" : request.steps();
        try { mapper.readTree(steps); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "steps must contain valid JSON"); }
        List<RecipeRequest.Ingredient> requestedItems = request.ingredients() == null ? List.of() : request.ingredients();
        Set<Long> ids = new HashSet<>();
        List<InventoryItem> ingredients = new ArrayList<>();
        for (RecipeRequest.Ingredient requested : requestedItems) {
            if (!ids.add(requested.ingredientId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An ingredient can appear only once in a recipe");
            InventoryItem ingredient = inventory.findById(requested.ingredientId()).orElse(null);
            if (ingredient == null || ingredient.getRestaurant() == null
                    || !Objects.equals(ingredient.getRestaurantId(), product.getRestaurantId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingredient must belong to the product branch");
            try { units.convert(requested.quantity(), requested.unit(), ingredient.getUnit()); }
            catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
            ingredients.add(ingredient);
        }
        Recipe previous = recipes.findFirstByProduct_IdAndActiveTrue(productId).orElse(null);
        int version = recipes.findFirstByProduct_IdOrderByVersionDesc(productId).map(Recipe::getVersion).orElse(0) + 1;
        if (previous != null) { previous.setActive(false); recipes.saveAndFlush(previous); }
        Recipe recipe = new Recipe(); recipe.setProduct(product); recipe.setVersion(version); recipe.setSteps(steps);
        recipe.setPrepMinutes(request.prepMinutes()); recipe.setCookMinutes(request.cookMinutes());
        recipe.setPortion(request.portion()); recipe.setDifficulty(request.difficulty()); recipe.setVideoUrl(request.videoUrl());
        recipe = recipes.saveAndFlush(recipe);
        for (int i = 0; i < requestedItems.size(); i++) {
            RecipeRequest.Ingredient requested = requestedItems.get(i);
            RecipeItem item = new RecipeItem(); item.setRecipe(recipe); item.setProduct(product);
            item.setTenantId(tenantId); item.setIngredient(ingredients.get(i)); item.setQuantity(requested.quantity());
            item.setUnit(requested.unit().trim().toUpperCase(Locale.ROOT)); items.save(item);
        }
        return response(recipe, requestedItems.isEmpty());
    }
    @Transactional(readOnly = true)
    public Map<String, Object> get(Long productId, Long tenantId) {
        Product product = products.findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        Recipe recipe = recipes.findFirstByProduct_IdAndActiveTrue(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipe not found"));
        return response(recipe, items.findByRecipe_IdOrderByIdAsc(recipe.getId()).isEmpty());
    }
    @Transactional(readOnly = true)
    public Map<String, Object> cost(Long productId, Long tenantId) {
        Product product = products.findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        Recipe recipe = recipes.findFirstByProduct_IdAndActiveTrue(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipe not found"));
        BigDecimal total = BigDecimal.ZERO;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RecipeItem item : items.findByRecipe_IdOrderByIdAsc(recipe.getId())) {
            BigDecimal quantity = units.convert(item.getQuantity(), item.getUnit(), item.getIngredient().getUnit());
            BigDecimal line = quantity.multiply(item.getIngredient().getUnitCost()); total = total.add(line);
            rows.add(Map.of("ingredientId", item.getIngredient().getId(), "ingredient", item.getIngredient().getItemName(),
                    "quantity", quantity, "unit", item.getIngredient().getUnit(), "unitCost", item.getIngredient().getUnitCost(), "cost", line));
        }
        BigDecimal perPortion = recipe.getPortion() == null || recipe.getPortion().signum() == 0
                ? null : total.divide(recipe.getPortion(), 4, java.math.RoundingMode.HALF_UP);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("productId", product.getId());
        result.put("recipeVersion", recipe.getVersion()); result.put("currency", "INR");
        result.put("batchCost", total); result.put("portionYield", recipe.getPortion()); result.put("costPerPortion", perPortion);
        result.put("ingredients", rows); result.put("warning", rows.isEmpty() ? "Recipe has no ingredients; no BOM deduction will be created" : null);
        return result;
    }
    private Map<String, Object> response(Recipe recipe, boolean emptyWarning) {
        List<Map<String, Object>> ingredientRows = items.findByRecipe_IdOrderByIdAsc(recipe.getId()).stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("ingredientId", item.getIngredient().getId());
            row.put("name", item.getIngredient().getItemName()); row.put("quantity", item.getQuantity()); row.put("unit", item.getUnit()); return row;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>(); result.put("id", recipe.getId());
        result.put("productId", recipe.getProduct().getId()); result.put("version", recipe.getVersion());
        result.put("isActive", recipe.isActive()); result.put("steps", recipe.getSteps());
        result.put("prepMinutes", recipe.getPrepMinutes()); result.put("cookMinutes", recipe.getCookMinutes());
        result.put("portion", recipe.getPortion()); result.put("difficulty", recipe.getDifficulty());
        result.put("videoUrl", recipe.getVideoUrl()); result.put("ingredients", ingredientRows);
        if (emptyWarning) result.put("warning", "Recipe has no ingredients; no BOM deduction will be created");
        return result;
    }
}
