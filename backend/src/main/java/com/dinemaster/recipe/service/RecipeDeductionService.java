package com.dinemaster.recipe.service;

import com.dinemaster.inventory.entity.InventoryItem;
import com.dinemaster.inventory.repository.InventoryRepository;
import com.dinemaster.inventory.service.InventoryStockService;
import com.dinemaster.orders.entity.Order;
import com.dinemaster.orders.entity.OrderItem;
import com.dinemaster.orders.repository.OrderItemRepository;
import com.dinemaster.orders.repository.OrderRepository;
import com.dinemaster.recipe.entity.Recipe;
import com.dinemaster.recipe.entity.RecipeItem;
import com.dinemaster.recipe.repository.RecipeItemRepository;
import com.dinemaster.recipe.repository.RecipeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RecipeDeductionService {
    private static final Logger log = LoggerFactory.getLogger(RecipeDeductionService.class);
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final RecipeRepository recipes;
    private final RecipeItemRepository recipeItems;
    private final InventoryRepository inventory;
    private final InventoryStockService stock;
    private final UnitConversionService units;
    private final JdbcTemplate jdbc;

    public RecipeDeductionService(OrderRepository orders, OrderItemRepository orderItems,
            RecipeRepository recipes, RecipeItemRepository recipeItems, InventoryRepository inventory,
            InventoryStockService stock, UnitConversionService units, JdbcTemplate jdbc) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.recipes = recipes;
        this.recipeItems = recipeItems;
        this.inventory = inventory;
        this.stock = stock;
        this.units = units;
        this.jdbc = jdbc;
    }

    @Transactional
    public void process(Long orderId) {
        String eventKey = "ORDER_PAID:" + orderId;
        if (jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM processed_events WHERE event_key = ?)", Boolean.class, eventKey)) return;
        Order order = orders.findById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!"Paid".equalsIgnoreCase(order.getPaymentStatus())) throw new IllegalStateException("Order is not paid");
        Map<Long, BigDecimal> required = new LinkedHashMap<>();
        Map<Long, InventoryItem> ingredients = new LinkedHashMap<>();
        for (OrderItem sold : orderItems.findByOrder_Id(orderId)) {
            Long recipeId = jdbc.query("SELECT recipe_id FROM order_recipe_snapshots WHERE order_item_id=?",
                    rs -> rs.next() ? rs.getObject(1, Long.class) : null, sold.getId());
            if (recipeId == null) {
                log.warn("Paid order {} contains product {} without a recipe; no BOM deduction applied",
                        orderId, sold.getProduct().getId());
                continue;
            }
            Recipe recipe = recipes.findById(recipeId).orElseThrow(() -> new IllegalStateException("Order recipe snapshot is missing"));
            var recipeLines = recipeItems.findByRecipe_IdOrderByIdAsc(recipe.getId());
            if (recipeLines.isEmpty()) log.warn("Recipe {} has no ingredients; no BOM deduction applied", recipe.getId());
            for (RecipeItem line : recipeLines) {
                InventoryItem ingredient = line.getIngredient();
                if (!ingredient.getRestaurant().getId().equals(order.getRestaurant().getId()))
                    throw new IllegalStateException("Recipe ingredient belongs to another restaurant");
                BigDecimal amount = units.convert(line.getQuantity(), line.getUnit(), ingredient.getUnit())
                        .multiply(BigDecimal.valueOf(sold.getQuantity()));
                required.merge(ingredient.getId(), amount, BigDecimal::add);
                ingredients.put(ingredient.getId(), ingredient);
            }
        }
        for (Map.Entry<Long, BigDecimal> line : required.entrySet()) {
            InventoryItem ingredient = ingredients.get(line.getKey());
            BigDecimal amount = line.getValue().setScale(3, RoundingMode.CEILING);
            stock.consume(ingredient, amount, "SALE", "ORDER", orderId);
            inventory.save(ingredient);
        }
        jdbc.update("INSERT INTO processed_events(event_key) VALUES (?) ON CONFLICT (event_key) DO NOTHING", eventKey);
    }
}
