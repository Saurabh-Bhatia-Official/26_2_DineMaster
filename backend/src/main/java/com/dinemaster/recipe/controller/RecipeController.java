package com.dinemaster.recipe.controller;

import com.dinemaster.catalog.repository.ProductRepository;
import com.dinemaster.recipe.dto.RecipeRequest;
import com.dinemaster.recipe.service.RecipeService;
import com.dinemaster.security.BranchScopeService;
import com.dinemaster.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/recipes")
public class RecipeController {
    private final RecipeService recipes;
    private final ProductRepository products;
    private final BranchScopeService branchScope;
    public RecipeController(RecipeService recipes, ProductRepository products, BranchScopeService branchScope) {
        this.recipes = recipes; this.products = products; this.branchScope = branchScope;
    }
    @GetMapping("/{productId}")
    @PreAuthorize("hasAuthority('recipe:view')")
    public ResponseEntity<?> get(@PathVariable Long productId, Authentication authentication) {
        User actor = branchScope.requireUser(authentication);
        var product = products.findByIdAndTenantIdAndDeletedAtIsNull(productId, actor.getTenantId()).orElse(null);
        if (product == null) return ResponseEntity.notFound().build();
        branchScope.requireBranch(authentication, product.getRestaurantId());
        return ResponseEntity.ok(recipes.get(productId, actor.getTenantId()));
    }
    @PutMapping("/{productId}")
    @PreAuthorize("hasAuthority('recipe:manage')")
    public ResponseEntity<?> save(@PathVariable Long productId, @Valid @RequestBody RecipeRequest request,
            Authentication authentication) {
        User actor = branchScope.requireUser(authentication);
        var product = products.findByIdAndTenantIdAndDeletedAtIsNull(productId, actor.getTenantId()).orElse(null);
        if (product == null) return ResponseEntity.notFound().build();
        branchScope.requireBranch(authentication, product.getRestaurantId());
        return ResponseEntity.ok(recipes.save(productId, actor.getTenantId(), request));
    }
    @GetMapping("/{productId}/cost")
    @PreAuthorize("hasAuthority('recipe:view')")
    public ResponseEntity<?> cost(@PathVariable Long productId, Authentication authentication) {
        User actor = branchScope.requireUser(authentication);
        var product = products.findByIdAndTenantIdAndDeletedAtIsNull(productId, actor.getTenantId()).orElse(null);
        if (product == null) return ResponseEntity.notFound().build();
        branchScope.requireBranch(authentication, product.getRestaurantId());
        return ResponseEntity.ok(recipes.cost(productId, actor.getTenantId()));
    }
    @GetMapping
    @PreAuthorize("hasAuthority('recipe:view')")
    @Transactional(readOnly = true)
    public ResponseEntity<?> legacyGet(@RequestParam Long productId, Authentication authentication) {
        return get(productId, authentication).getStatusCode().is2xxSuccessful()
                ? ResponseEntity.ok(java.util.List.of(recipes.get(productId, branchScope.requireUser(authentication).getTenantId())))
                : ResponseEntity.notFound().build();
    }
}
