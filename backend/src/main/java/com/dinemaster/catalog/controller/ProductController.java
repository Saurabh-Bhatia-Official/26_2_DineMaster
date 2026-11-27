package com.dinemaster.catalog.controller;




import com.dinemaster.catalog.entity.Category;
import com.dinemaster.catalog.entity.Product;
import com.dinemaster.catalog.dto.ProductAvailabilityRequest;
import com.dinemaster.catalog.repository.CategoryRepository;
import com.dinemaster.catalog.repository.ProductRepository;
import com.dinemaster.catalog.repository.TaxSlabRepository;
import com.dinemaster.catalog.service.ImageStorageService;
import com.dinemaster.catalog.service.ProductService;
import com.dinemaster.cache.CacheInvalidationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dinemaster.restaurant.entity.Restaurant;
import com.dinemaster.restaurant.repository.RestaurantRepository;
import com.dinemaster.security.BranchScopeService;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.MultiValueMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.dinemaster.user.entity.User;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final RestaurantRepository restaurantRepository;
    private final BranchScopeService branchScope;
    private final ImageStorageService imageStorage;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final TaxSlabRepository taxSlabs;
    private final ProductService productService;
    private final CacheInvalidationService cacheInvalidation;

    public ProductController(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            RestaurantRepository restaurantRepository,
            BranchScopeService branchScope,
            ImageStorageService imageStorage,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc,
            TaxSlabRepository taxSlabs,
            ProductService productService,
            CacheInvalidationService cacheInvalidation) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.restaurantRepository = restaurantRepository;
        this.branchScope = branchScope;
        this.imageStorage = imageStorage;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.taxSlabs = taxSlabs;
        this.productService = productService;
        this.cacheInvalidation = cacheInvalidation;
    }

    // GET: Fetch available products
    @GetMapping
    @PreAuthorize("hasAuthority('menu:view')")
    public ResponseEntity<List<Product>> getAllProducts(
            @RequestParam(required = false) Long restaurantId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Boolean available,
            Authentication authentication) {
        User actor = branchScope.requireUser(authentication);
        List<Product> rows;
        if (restaurantId != null) {
            branchScope.requireBranch(authentication, restaurantId);
            rows = productRepository.findByRestaurant_IdAndDeletedAtIsNullOrderByNameAsc(restaurantId);
        } else {
            rows = productRepository.findByTenantIdAndDeletedAtIsNullOrderByNameAsc(actor.getTenantId());
        }
        return ResponseEntity.ok(rows.stream()
                .filter(p -> available == null || p.isAvailable() == available)
                .filter(p -> categoryId == null || p.getCategoryRef() != null && categoryId.equals(p.getCategoryRef().getId()))
                .filter(p -> search == null || search.isBlank() || p.getName().toLowerCase().contains(search.trim().toLowerCase())
                        || p.getSku() != null && p.getSku().toLowerCase().contains(search.trim().toLowerCase()))
                .toList());
    }

    // GET: Fetch all products, including unavailable products
    @GetMapping("/all")
    @PreAuthorize("hasAuthority('menu:view')")
    public ResponseEntity<List<Product>> getAllProductsForManagement(@RequestParam Long restaurantId, Authentication authentication) {
        branchScope.requireBranch(authentication, restaurantId);
        return ResponseEntity.ok(productRepository.findByRestaurant_IdAndDeletedAtIsNullOrderByNameAsc(restaurantId));
    }

    // GET: Fetch a product by ID
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('menu:view')")
    public ResponseEntity<?> getProductById(@PathVariable Long id, Authentication authentication) {
        User actor = branchScope.requireUser(authentication);
        return productRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, actor.getTenantId())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Product not found")));
    }

    // GET: Fetch products by category name
    @GetMapping("/category/{category}")
    @PreAuthorize("hasAuthority('menu:view')")
    public ResponseEntity<List<Product>> getProductsByCategory(
            @PathVariable String category,
            @RequestParam(required = false) Long restaurantId,
            Authentication authentication) {
        User actor = branchScope.requireUser(authentication);
        if (restaurantId != null) branchScope.requireBranch(authentication, restaurantId);
        List<Product> rows = restaurantId == null
                ? productRepository.findByTenantIdAndDeletedAtIsNullOrderByNameAsc(actor.getTenantId())
                : productRepository.findByRestaurant_IdAndDeletedAtIsNullOrderByNameAsc(restaurantId);
        return ResponseEntity.ok(rows.stream().filter(Product::isAvailable)
                .filter(p -> p.getCategory() != null && p.getCategory().equalsIgnoreCase(category)).toList());
    }

    // POST: Create a product
    @PostMapping
    @PreAuthorize("hasAuthority('menu:manage')")
    public ResponseEntity<?> createProduct(
            @Valid @RequestBody Product product, Authentication authentication) {
        return saveProduct(product, null, authentication);
    }

    /** Create a product and upload its image in the same multipart request. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('menu:manage')")
    public ResponseEntity<?> createProductWithImage(
            @RequestParam MultiValueMap<String, String> formFields,
            @RequestPart("image") MultipartFile image,
            Authentication authentication) {
        if (image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Product image is required"));
        }
        final Product product;
        try {
            String productJson = formFields.getFirst("product");
            product = productJson == null
                    ? objectMapper.convertValue(formFields.toSingleValueMap(), Product.class)
                    : objectMapper.readValue(productJson, Product.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Provide valid product fields or valid JSON in the product field"));
        }
        return saveProduct(product, image, authentication);
    }

    /** Upload an image for a product that was already created. */
    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('menu:manage')")
    public ResponseEntity<?> uploadImageForExistingProduct(
            @PathVariable Long id,
            @RequestPart("image") MultipartFile image,
            Authentication authentication) {
        if (image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Product image is required"));
        }
        User actor = branchScope.requireUser(authentication);
        return productRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, actor.getTenantId())
                .<ResponseEntity<?>>map(product -> {
                    branchScope.requireBranch(authentication, product.getRestaurantId());
                    ResponseEntity<?> uploadError = uploadProductImage(product, image);
                    if (uploadError != null) return uploadError;
                    Product saved = productRepository.save(product);
                    cacheInvalidation.invalidateBranchCatalog(saved.getTenantId(), saved.getRestaurantId());
                    return ResponseEntity.ok(saved);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Product not found")));
    }

    private ResponseEntity<?> saveProduct(
            Product product, MultipartFile image, Authentication authentication) {
        ResponseEntity<Map<String, String>> validation = validateProduct(product);

        if (validation != null) {
            return ResponseEntity.badRequest().body(validation.getBody());
        }
        branchScope.requireBranch(authentication, product.getRestaurantId());
        User actor = branchScope.requireUser(authentication);
        if (productService.skuTaken(actor.getTenantId(), product.getSku(), null)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "SKU already exists for this tenant"));
        }

        String categoryName = findCategoryName(product.getCategory(), product.getRestaurantId());

        if (categoryName == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Category does not exist. Create the category first."));
        }

        product.setId(null);
        product.setTenantId(actor.getTenantId());
        product.setSku(product.getSku() == null || product.getSku().isBlank() ? null : product.getSku().trim());
        product.setName(product.getName().trim());
        product.setCategory(categoryName);
        product.setCategoryRef(findCategory(product.getCategory(), product.getRestaurantId()));
        ResponseEntity<?> restaurantError = attachRestaurant(product);
        if (restaurantError != null) {
            return restaurantError;
        }

        if (product.getGstPercentage() == null) {
            product.setGstPercentage(new BigDecimal("5.00"));
        }
        var taxSlab = taxSlabs.findByTenantIdAndRatePercentAndActiveTrue(actor.getTenantId(), product.getGstPercentage()).orElse(null);
        if (taxSlab == null) return ResponseEntity.badRequest().body(Map.of("error", "Select an active GST slab for this tenant"));
        product.setTaxSlab(taxSlab);

        if (image != null && !image.isEmpty()) {
            ResponseEntity<?> imageError = uploadProductImage(product, image);
            if (imageError != null) return imageError;
        }

        Product savedProduct = productRepository.save(product);
        cacheInvalidation.invalidateBranchCatalog(savedProduct.getTenantId(), savedProduct.getRestaurantId());

        return ResponseEntity.status(HttpStatus.CREATED).body(savedProduct);
    }

    private ResponseEntity<?> uploadProductImage(Product product, MultipartFile image) {
        try {
            product.setImagePath(imageStorage.store(image));
            return null;
        } catch (ImageStorageService.InvalidImageException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (ImageStorageService.StorageUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Image storage is not configured"));
        } catch (Exception e) {
            log.error("Image upload failed for product id={}", product.getId(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Could not upload product image"));
        }
    }

    // PUT: Update a product
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('menu:manage')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody Product request, Authentication authentication) {

        ResponseEntity<Map<String, String>> validation = validateProduct(request);

        if (validation != null) {
            return ResponseEntity.badRequest().body(validation.getBody());
        }
        if (request.getRestaurantId() != null) {
            branchScope.requireBranch(authentication, request.getRestaurantId());
        }
        User actor = branchScope.requireUser(authentication);
        return productRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, actor.getTenantId())
                .<ResponseEntity<?>>map(existingProduct -> {
                    Long branchId = request.getRestaurantId() == null
                            ? existingProduct.getRestaurantId() : request.getRestaurantId();
                    branchScope.requireBranch(authentication, branchId);
                    String categoryName = findCategoryName(request.getCategory(), branchId);

                    if (categoryName == null) {
                        return ResponseEntity.badRequest().body(
                                Map.of("error", "Category does not exist."));
                    }

                    String requestedSku = request.getSku() == null || request.getSku().isBlank() ? null : request.getSku().trim();
                    if (productService.skuTaken(actor.getTenantId(), requestedSku, id)) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "SKU already exists for this tenant"));
                    }

                    Long previousBranchId = existingProduct.getRestaurantId();
                    BigDecimal oldPrice = existingProduct.getPrice();
                    copyProductFields(request, existingProduct);
                    existingProduct.setTenantId(actor.getTenantId());
                    existingProduct.setSku(requestedSku);
                    existingProduct.setCategory(categoryName);
                    existingProduct.setCategoryRef(findCategory(request.getCategory(), branchId));
                    BigDecimal nextTaxRate = request.getGstPercentage() == null ? new BigDecimal("5.00") : request.getGstPercentage();
                    var taxSlab = taxSlabs.findByTenantIdAndRatePercentAndActiveTrue(actor.getTenantId(), nextTaxRate).orElse(null);
                    if (taxSlab == null) return ResponseEntity.badRequest().body(Map.of("error", "Select an active GST slab for this tenant"));
                    existingProduct.setTaxSlab(taxSlab);
                    if (request.getRestaurantId() != null) {
                        ResponseEntity<?> restaurantError = attachRestaurant(request);
                        if (restaurantError != null) {
                            return restaurantError;
                        }
                        existingProduct.setRestaurant(request.getRestaurant());
                    }

                    Product saved = productRepository.save(existingProduct);
                    cacheInvalidation.invalidateBranchCatalog(saved.getTenantId(), previousBranchId);
                    cacheInvalidation.invalidateBranchCatalog(saved.getTenantId(), saved.getRestaurantId());
                    if (oldPrice != null && oldPrice.compareTo(saved.getPrice()) != 0 && branchId != null) {
                        jdbc.update("""
                                INSERT INTO menu_price_audit(branch_id, product_id, old_price, new_price, changed_by)
                                VALUES (?, ?, ?, ?, ?)
                                """, branchId, saved.getId(), oldPrice, saved.getPrice(), authentication.getName());
                    }
                    return ResponseEntity.ok(saved);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Product not found")));
    }

    // PATCH: Update only product availability
    @RequestMapping(value = "/{id}/availability", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @PreAuthorize("hasAnyAuthority('menu:manage','menu:toggle_availability')")
    public ResponseEntity<?> updateAvailability(
            @PathVariable Long id,
            @Valid @RequestBody ProductAvailabilityRequest request, Authentication authentication) {
        User actor = branchScope.requireUser(authentication);
        return productRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, actor.getTenantId())
                .<ResponseEntity<?>>map(product -> {
                    branchScope.requireBranch(authentication, product.getRestaurantId());
                    return ResponseEntity.ok(productService.setAvailability(product, request.available()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Product not found")));
    }
    // DELETE: Delete a product
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('menu:manage')")
    public ResponseEntity<?> archiveProduct(@PathVariable Long id, Authentication authentication) {
        User actor = branchScope.requireUser(authentication);
        Product product = productRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, actor.getTenantId()).orElse(null);
        if (product == null) return ResponseEntity.notFound().build();
        branchScope.requireBranch(authentication, product.getRestaurantId());
        return ResponseEntity.ok(productService.archive(product));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('menu:manage')")
    public ResponseEntity<?> deleteProduct(@PathVariable Long id, Authentication authentication) {
        User actor = branchScope.requireUser(authentication);
        Product product = productRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, actor.getTenantId()).orElse(null);
        if (product == null) return ResponseEntity.notFound().build();
        branchScope.requireBranch(authentication, product.getRestaurantId());
        boolean archived = productService.deleteOrArchive(product);
        return archived ? ResponseEntity.ok(Map.of("status", "ARCHIVED", "id", product.getId()))
                : ResponseEntity.noContent().build();
    }

    private ResponseEntity<Map<String, String>> validateProduct(
            Product product) {

        if (product == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Product data is required"));
        }

        if (product.getName() == null || product.getName().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Product name is required"));
        }

        if (product.getName().trim().length() > 150) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Product name cannot exceed 150 characters"));
        }
        if (product.getName().trim().length() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "Product name must contain at least 2 characters"));
        }
        if (product.getSku() != null && product.getSku().trim().length() > 40) {
            return ResponseEntity.badRequest().body(Map.of("error", "SKU cannot exceed 40 characters"));
        }

        if (product.getPrice() == null
                || product.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A non-negative product price is required"));
        }

        if (product.getCategory() == null
                || product.getCategory().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Product category is required"));
        }

        if (product.getVeg() == null || product.getVeg() < 0 || product.getVeg() > 3) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "is_veg must be one of 0, 1, 2, or 3"));
        }

        if (product.getGstPercentage() != null
                && (product.getGstPercentage().compareTo(BigDecimal.ZERO) < 0
                        || product.getGstPercentage()
                                .compareTo(new BigDecimal("100")) > 0)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "GST percentage must be between 0 and 100"));
        }

        return null;
    }

    private ResponseEntity<?> attachRestaurant(Product product) {
        Long restaurantId = product.getRestaurantId();
        if (restaurantId == null) {
            return null;
        }
        Restaurant restaurant = restaurantRepository.findById(restaurantId).orElse(null);
        if (restaurant == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Restaurant not found"));
        }
        product.setRestaurant(restaurant);
        return null;
    }

    private String findCategoryName(String requestedName, Long restaurantId) {
        if (requestedName == null || requestedName.isBlank() || restaurantId == null) {
            return null;
        }
        return restaurantRepository.findById(restaurantId)
                .flatMap(restaurant -> categoryRepository.findFirstByNameIgnoreCaseAndTenantId(
                        requestedName.trim(), restaurant.getTenantId()))
                .filter(category -> category.getDeletedAt() == null && category.isActive())
                .map(Category::getName).orElse(null);
    }

    private Category findCategory(String requestedName, Long restaurantId) {
        if (requestedName == null || requestedName.isBlank() || restaurantId == null) return null;
        return restaurantRepository.findById(restaurantId)
                .flatMap(branch -> categoryRepository.findFirstByNameIgnoreCaseAndTenantId(
                        requestedName.trim(), branch.getTenantId()))
                .filter(category -> category.getDeletedAt() == null && category.isActive()).orElse(null);
    }

    private void copyProductFields(Product source, Product target) {
        target.setName(source.getName().trim());
        target.setDescription(source.getDescription());
        target.setIngredients(source.getIngredients());
        target.setPrice(source.getPrice());
        target.setVeg(source.getVeg());
        // A null image path means the caller did not replace the image.
        if (source.getImagePath() != null) {
            target.setImagePath(source.getImagePath());
        }
        target.setAvailable(source.isAvailable());
        target.setGstPercentage(
                source.getGstPercentage() == null
                        ? new BigDecimal("5.00")
                        : source.getGstPercentage());
        target.setDietaryPreferences(source.getDietaryPreferences());
        target.setTastePreferences(source.getTastePreferences());
        target.setAttributes(source.getAttributes());
        target.setCustomDietaryNotes(source.getCustomDietaryNotes());
        target.setRecipeSteps(source.getRecipeSteps());
        target.setPrepTime(source.getPrepTime());
        target.setCookTime(source.getCookTime());
        target.setServings(source.getServings());
        target.setDifficulty(source.getDifficulty());
        target.setVideoPath(source.getVideoPath());
        target.setStation(source.getStation());
        target.setDietaryTags(source.getDietaryTags());
        target.setBadges(source.getBadges());
        target.setCookingMetadata(source.getCookingMetadata());
    }
}
