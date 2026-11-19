package com.dinemaster.catalog.controller;




import com.dinemaster.catalog.dto.CategoryRequest;
import com.dinemaster.catalog.entity.Category;
import com.dinemaster.catalog.repository.CategoryRepository;
import com.dinemaster.catalog.repository.ProductRepository;

import jakarta.validation.Valid;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import com.dinemaster.security.BranchScopeService;
import com.dinemaster.user.entity.User;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryRepository categoryRepository;
    private final BranchScopeService branchScope;
    private final ProductRepository products;

    public CategoryController(CategoryRepository categoryRepository, BranchScopeService branchScope, ProductRepository products) {
        this.categoryRepository = categoryRepository;
        this.branchScope = branchScope;
        this.products = products;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('menu:view')")
    public ResponseEntity<List<Category>> getAllCategories(Authentication authentication) {
        User user = branchScope.requireUser(authentication);
        return ResponseEntity.ok(categoryRepository.findAllByTenantIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(user.getTenantId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('menu:view')")
    public ResponseEntity<?> getCategoryById(@PathVariable Long id, Authentication authentication) {
        User user = branchScope.requireUser(authentication);
        return categoryRepository.findByIdAndTenantId(id, user.getTenantId()).filter(category -> category.getDeletedAt() == null)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Category not found")));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('menu:manage')")
    public ResponseEntity<?> createCategory(
            @Valid @RequestBody CategoryRequest request, Authentication authentication) {

        User user = branchScope.requireUser(authentication);

        String name = request.getName().trim();

        if (categoryRepository.existsByNameIgnoreCaseAndTenantId(name, user.getTenantId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Category already exists"));
        }

        Category category = new Category();
        category.setName(name);
        category.setTenantId(user.getTenantId());
        category.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        if (request.getActive() != null) category.setActive(request.getActive());

        Category savedCategory = categoryRepository.save(category);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(savedCategory);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('menu:manage')")
    public ResponseEntity<?> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody CategoryRequest request, Authentication authentication) {

        User user = branchScope.requireUser(authentication);

        return categoryRepository.findByIdAndTenantId(id, user.getTenantId()).filter(category -> category.getDeletedAt() == null)
                .<ResponseEntity<?>>map(category -> {
                    String name = request.getName().trim();

                    if (categoryRepository.existsByNameIgnoreCaseAndTenantId(name, user.getTenantId())
                            && !category.getName().equalsIgnoreCase(name)) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("error", "Category already exists"));
                    }

                    category.setName(name);
                    if (request.getSortOrder() != null) category.setSortOrder(request.getSortOrder());
                    if (request.getActive() != null) category.setActive(request.getActive());
                    return ResponseEntity.ok(categoryRepository.save(category));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Category not found")));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('menu:manage')")
    @Transactional
    public ResponseEntity<?> deleteCategory(@PathVariable Long id, Authentication authentication) {
        User user = branchScope.requireUser(authentication);
        return categoryRepository.findByIdAndTenantId(id, user.getTenantId()).filter(category -> category.getDeletedAt() == null)
                .<ResponseEntity<?>>map(category -> {
                    if (products.countByCategoryRef_Id(category.getId()) > 0) {
                        category.setActive(false);
                        category.setDeletedAt(OffsetDateTime.now());
                        categoryRepository.save(category);
                        return ResponseEntity.ok(Map.of("status", "ARCHIVED", "id", category.getId()));
                    }
                    categoryRepository.delete(category);
                    return ResponseEntity.noContent().build();
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Category not found")));
    }

    public record ReorderItem(Long id, Integer sortOrder) { }
    public record ReorderRequest(List<ReorderItem> items) { }

    @PutMapping("/reorder")
    @PreAuthorize("hasAuthority('menu:manage')")
    @Transactional
    public ResponseEntity<?> reorder(@RequestBody ReorderRequest request, Authentication authentication) {
        User user = branchScope.requireUser(authentication);
        if (request.items() == null || request.items().isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "items are required"));
        List<Long> ids = request.items().stream().map(ReorderItem::id).toList();
        if (ids.contains(null) || ids.stream().distinct().count() != ids.size()
                || request.items().stream().anyMatch(item -> item.sortOrder() == null || item.sortOrder() < 0)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Category IDs must be unique and sortOrder must be non-negative"));
        }
        List<Category> categories = new ArrayList<>();
        for (Long id : ids) {
            Category category = categoryRepository.findByIdAndTenantId(id, user.getTenantId()).filter(c -> c.getDeletedAt() == null).orElse(null);
            if (category == null) return ResponseEntity.badRequest().body(Map.of("error", "Every category must belong to the current tenant"));
            categories.add(category);
        }
        for (int i = 0; i < request.items().size(); i++) categories.get(i).setSortOrder(request.items().get(i).sortOrder());
        return ResponseEntity.ok(categoryRepository.saveAll(categories));
    }
}
