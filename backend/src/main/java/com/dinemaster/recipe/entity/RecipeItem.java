package com.dinemaster.recipe.entity;
import com.dinemaster.catalog.entity.Product;
import com.dinemaster.inventory.entity.InventoryItem;
import jakarta.persistence.*;
import java.math.BigDecimal;
@Entity
@Table(name = "recipe_items", uniqueConstraints = @UniqueConstraint(columnNames = {"recipe_id", "ingredient_id"}))
public class RecipeItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "recipe_id", nullable = false) private Recipe recipe;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "product_id", nullable = false) private Product product;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "ingredient_id", nullable = false) private InventoryItem ingredient;
    @Column(nullable = false, precision = 14, scale = 4) private BigDecimal quantity;
    @Column(nullable = false, length = 30) private String unit;
    public Long getId() { return id; }
    public Recipe getRecipe() { return recipe; }
    public void setRecipe(Recipe value) { recipe = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public Product getProduct() { return product; }
    public void setProduct(Product value) { product = value; }
    public InventoryItem getIngredient() { return ingredient; }
    public void setIngredient(InventoryItem value) { ingredient = value; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal value) { quantity = value; }
    public String getUnit() { return unit; }
    public void setUnit(String value) { unit = value; }
}
