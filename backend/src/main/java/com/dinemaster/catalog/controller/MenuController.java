package com.dinemaster.catalog.controller;

import com.dinemaster.catalog.entity.BranchProduct;
import com.dinemaster.catalog.entity.BranchProductId;
import com.dinemaster.catalog.entity.Category;
import com.dinemaster.catalog.entity.Product;
import com.dinemaster.catalog.repository.BranchProductRepository;
import com.dinemaster.catalog.repository.CategoryRepository;
import com.dinemaster.catalog.repository.ProductRepository;
import com.dinemaster.restaurant.repository.RestaurantRepository;
import com.dinemaster.security.BranchScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class MenuController {
    private final BranchProductRepository branchProducts;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final RestaurantRepository branches;
    private final BranchScopeService branchScope;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    public MenuController(BranchProductRepository branchProducts, CategoryRepository categories,
            ProductRepository products, RestaurantRepository branches, BranchScopeService branchScope,
            ObjectMapper mapper, JdbcTemplate jdbc) {
        this.branchProducts = branchProducts; this.categories = categories; this.products = products;
        this.branches = branches; this.branchScope = branchScope; this.mapper = mapper; this.jdbc = jdbc;
    }

    public record Availability(Long productId, Boolean isAvailable, BigDecimal priceOverride) { }
    public record AvailabilityRequest(List<@Valid Availability> items) { }

    @GetMapping("/menu")
    @PreAuthorize("hasAuthority('menu:view')")
    @Transactional(readOnly = true)
    public ResponseEntity<?> menu(@RequestParam Long branchId, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            Authentication authentication) throws Exception {
        branchScope.requireBranch(authentication, branchId);
        var branch = branches.findById(branchId).orElse(null);
        if (branch == null) return ResponseEntity.notFound().build();
        List<Map<String, Object>> categoryRows = new ArrayList<>();
        for (Category category : categories.findAllByTenantIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(branch.getTenantId())) {
            if (!category.isActive()) continue;
            List<Map<String, Object>> productRows = new ArrayList<>();
            for (Product product : products.findByRestaurant_IdAndCategoryRef_IdAndAvailableTrue(branchId, category.getId())) {
                BranchProduct config = branchProducts.findByBranchIdAndProductId(branchId, product.getId()).orElse(null);
                if (config != null && !config.isAvailable()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", product.getId()); row.put("name", product.getName());
                row.put("description", product.getDescription());
                row.put("price", config != null && config.getPriceOverride() != null ? config.getPriceOverride() : product.getPrice());
                row.put("taxRate", product.getGstPercentage()); row.put("imagePath", product.getImagePath());
                productRows.add(row);
            }
            categoryRows.add(Map.of("id", category.getId(), "name", category.getName(),
                    "sortOrder", category.getSortOrder(), "products", productRows));
        }
        Map<String, Object> versionInput = Map.of("branchId", branchId, "categories", categoryRows);
        String contentHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsString(versionInput).getBytes(StandardCharsets.UTF_8)));
        long menuVersion = Long.parseUnsignedLong(contentHash.substring(0, 15), 16);
        Map<String, Object> body = Map.of("branchId", branchId, "menuVersion", menuVersion, "categories", categoryRows);
        String etag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8))) + "\"";
        if (etag.equals(ifNoneMatch)) return ResponseEntity.status(304).eTag(etag).build();
        return ResponseEntity.ok().eTag(etag).body(body);
    }

    @GetMapping("/branches/{branchId}/availability")
    @PreAuthorize("hasAuthority('menu:view')")
    public ResponseEntity<?> availability(@PathVariable Long branchId, Authentication authentication) {
        branchScope.requireBranch(authentication, branchId);
        Map<Long, BranchProduct> configured = new HashMap<>();
        branchProducts.findByBranchId(branchId).forEach(value -> configured.put(value.getProductId(), value));
        return ResponseEntity.ok(products.findByRestaurant_Id(branchId).stream().map(product -> {
            BranchProduct value = configured.get(product.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", product.getId()); row.put("name", product.getName());
            row.put("isAvailable", value == null ? product.isAvailable() : value.isAvailable());
            row.put("price", value != null && value.getPriceOverride() != null ? value.getPriceOverride() : product.getPrice());
            row.put("priceOverride", value == null ? null : value.getPriceOverride());
            return row;
        }).toList());
    }

    @PutMapping("/branches/{branchId}/availability")
    @PreAuthorize("hasAuthority('menu:manage')")
    @Transactional
    public ResponseEntity<?> updateAvailability(@PathVariable Long branchId, @Valid @RequestBody AvailabilityRequest request,
            Authentication authentication) {
        branchScope.requireBranch(authentication, branchId);
        if (request.items() == null || request.items().isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "items are required"));
        if (request.items().stream().anyMatch(i -> i.productId() == null || i.isAvailable() == null
                || i.priceOverride() != null && i.priceOverride().compareTo(BigDecimal.ZERO) < 0)
                || request.items().stream().map(Availability::productId).distinct().count() != request.items().size()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or duplicate product availability entry"));
        }
        List<BranchProduct> saved = new ArrayList<>();
        for (Availability item : request.items()) {
            Product product = products.findByIdAndRestaurant_Id(item.productId(), branchId).orElse(null);
            if (product == null) return ResponseEntity.badRequest().body(Map.of("error", "Product does not belong to this branch"));
            BranchProduct config = branchProducts.findByBranchIdAndProductId(branchId, item.productId())
                    .orElseGet(() -> { BranchProduct value = new BranchProduct(); value.setBranchId(branchId); value.setProductId(item.productId()); return value; });
            BigDecimal oldPrice = config.getPriceOverride() == null ? product.getPrice() : config.getPriceOverride();
            config.setAvailable(item.isAvailable()); config.setPriceOverride(item.priceOverride());
            BranchProduct savedConfig = branchProducts.save(config); saved.add(savedConfig);
            BigDecimal newPrice = item.priceOverride() == null ? product.getPrice() : item.priceOverride();
            if (oldPrice.compareTo(newPrice) != 0) jdbc.update("""
                    INSERT INTO menu_price_audit(branch_id, product_id, old_price, new_price, changed_by)
                    VALUES (?, ?, ?, ?, ?)
                    """, branchId, item.productId(), oldPrice, newPrice, authentication.getName());
        }
        return ResponseEntity.ok(saved);
    }
}
