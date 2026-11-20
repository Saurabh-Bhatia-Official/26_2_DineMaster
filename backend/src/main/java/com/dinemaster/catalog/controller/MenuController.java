package com.dinemaster.catalog.controller;

import com.dinemaster.catalog.entity.BranchProduct;
import com.dinemaster.catalog.entity.BranchProductId;
import com.dinemaster.catalog.entity.Category;
import com.dinemaster.catalog.entity.Product;
import com.dinemaster.catalog.repository.BranchProductRepository;
import com.dinemaster.catalog.repository.CategoryRepository;
import com.dinemaster.catalog.repository.ProductRepository;
import com.dinemaster.cache.CacheInvalidationService;
import com.dinemaster.cache.CatalogCacheService;
import com.dinemaster.catalog.dto.MenuCacheResponse;
import com.dinemaster.restaurant.repository.RestaurantRepository;
import com.dinemaster.security.BranchScopeService;
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
    private final CatalogCacheService cachedReads;
    private final CacheInvalidationService cacheInvalidation;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    public MenuController(BranchProductRepository branchProducts, CategoryRepository categories,
            ProductRepository products, RestaurantRepository branches, BranchScopeService branchScope,
            com.fasterxml.jackson.databind.ObjectMapper mapper, JdbcTemplate jdbc,
            CatalogCacheService cachedReads, CacheInvalidationService cacheInvalidation) {
        this.branchProducts = branchProducts; this.categories = categories; this.products = products;
        this.branches = branches; this.branchScope = branchScope; this.mapper = mapper; this.jdbc = jdbc;
        this.cachedReads = cachedReads; this.cacheInvalidation = cacheInvalidation;
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
        MenuCacheResponse catalog = cachedReads.branchMenu(branch.getTenantId(), branchId);
        List<Map<String, Object>> categoryRows = menuCategoryRows(catalog);
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
        var branch = branches.findById(branchId).orElse(null);
        if (branch == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(cachedReads.branchAvailability(branch.getTenantId(), branchId).items().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", item.productId()); row.put("name", item.name());
            row.put("isAvailable", item.isAvailable()); row.put("price", item.price());
            row.put("priceOverride", item.priceOverride());
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
            cacheInvalidation.invalidateBranchCatalog(product.getTenantId(), branchId);
            BigDecimal newPrice = item.priceOverride() == null ? product.getPrice() : item.priceOverride();
            if (oldPrice.compareTo(newPrice) != 0) jdbc.update("""
                    INSERT INTO menu_price_audit(branch_id, product_id, old_price, new_price, changed_by)
                    VALUES (?, ?, ?, ?, ?)
                    """, branchId, item.productId(), oldPrice, newPrice, authentication.getName());
        }
        return ResponseEntity.ok(saved);
    }

    private static List<Map<String, Object>> menuCategoryRows(MenuCacheResponse catalog) {
        return catalog.categories().stream().map(category -> {
            List<Map<String, Object>> products = category.products().stream().map(product -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", product.id()); row.put("name", product.name());
                row.put("description", product.description()); row.put("price", product.price());
                row.put("taxRate", product.taxRate()); row.put("imagePath", product.imagePath());
                return row;
            }).toList();
            return Map.<String, Object>of("id", category.id(), "name", category.name(),
                    "sortOrder", category.sortOrder(), "products", products);
        }).toList();
    }
}
