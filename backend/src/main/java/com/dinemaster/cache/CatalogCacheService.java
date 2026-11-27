package com.dinemaster.cache;

import com.dinemaster.catalog.dto.CategoryResponse;
import com.dinemaster.catalog.dto.MenuCacheResponse;
import com.dinemaster.catalog.entity.BranchProduct;
import com.dinemaster.catalog.entity.Category;
import com.dinemaster.catalog.entity.Product;
import com.dinemaster.catalog.repository.BranchProductRepository;
import com.dinemaster.catalog.repository.CategoryRepository;
import com.dinemaster.catalog.repository.ProductRepository;
import com.dinemaster.config.CacheConfig;
import com.dinemaster.restaurant.dto.BranchHoursResponse;
import com.dinemaster.restaurant.dto.BranchResponse;
import com.dinemaster.restaurant.entity.BranchHours;
import com.dinemaster.restaurant.entity.Restaurant;
import com.dinemaster.restaurant.repository.BranchHoursRepository;
import com.dinemaster.restaurant.repository.RestaurantRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogCacheService {
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final BranchProductRepository branchProducts;
    private final RestaurantRepository branches;
    private final BranchHoursRepository branchHours;

    public CatalogCacheService(CategoryRepository categories, ProductRepository products,
            BranchProductRepository branchProducts, RestaurantRepository branches,
            BranchHoursRepository branchHours) {
        this.categories = categories;
        this.products = products;
        this.branchProducts = branchProducts;
        this.branches = branches;
        this.branchHours = branchHours;
    }

    @Cacheable(cacheNames = CacheConfig.BRANCH_CATALOG,
            key = "'tenant:' + #tenantId + ':branch:' + #branchId")
    @Transactional(readOnly = true)
    public MenuCacheResponse branchMenu(Long tenantId, Long branchId) {
        Map<Long, BranchProduct> configs = new HashMap<>();
        branchProducts.findByBranchId(branchId)
                .forEach(config -> configs.put(config.getProductId(), config));

        List<MenuCacheResponse.Category> menuCategories = new ArrayList<>();
        for (Category category : categories.findAllByTenantIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(tenantId)) {
            if (!category.isActive()) continue;
            List<MenuCacheResponse.MenuItem> items = new ArrayList<>();
            for (Product product : products.findByRestaurant_IdAndCategoryRef_IdAndAvailableTrue(
                    branchId, category.getId())) {
                BranchProduct config = configs.get(product.getId());
                if (config != null && !config.isAvailable()) continue;
                items.add(new MenuCacheResponse.MenuItem(product.getId(), product.getName(),
                        product.getDescription(), effectivePrice(product, config),
                        product.getGstPercentage(), product.getImagePath()));
            }
            menuCategories.add(new MenuCacheResponse.Category(category.getId(), category.getName(),
                    category.getSortOrder(), List.copyOf(items)));
        }

        return new MenuCacheResponse(List.copyOf(menuCategories));
    }

    @Cacheable(cacheNames = CacheConfig.GUEST_MENU,
            key = "'tenant:' + #tenantId + ':branch:' + #branchId")
    @Transactional(readOnly = true)
    public MenuCacheResponse.GuestMenu guestMenu(Long tenantId, Long branchId) {
        Map<Long, BranchProduct> configs = new HashMap<>();
        branchProducts.findByBranchId(branchId)
                .forEach(config -> configs.put(config.getProductId(), config));
        List<MenuCacheResponse.GuestItem> guestItems = products.findByRestaurant_IdAndAvailableTrue(branchId).stream()
                .filter(product -> product.getDeletedAt() == null)
                .filter(product -> {
                    BranchProduct config = configs.get(product.getId());
                    return config == null || config.isAvailable();
                })
                .map(product -> new MenuCacheResponse.GuestItem(product.getId(), product.getName(),
                        product.getDescription(), product.getCategory(),
                        effectivePrice(product, configs.get(product.getId())), product.getImagePath(),
                        product.getVeg()))
                .toList();
        return new MenuCacheResponse.GuestMenu(guestItems);
    }

    @Cacheable(cacheNames = CacheConfig.BRANCH_AVAILABILITY,
            key = "'tenant:' + #tenantId + ':branch:' + #branchId")
    @Transactional(readOnly = true)
    public MenuCacheResponse.BranchAvailability branchAvailability(Long tenantId, Long branchId) {
        Map<Long, BranchProduct> configs = new HashMap<>();
        branchProducts.findByBranchId(branchId)
                .forEach(config -> configs.put(config.getProductId(), config));
        List<MenuCacheResponse.AvailabilityItem> availabilityItems = products.findByRestaurant_Id(branchId).stream()
                .map(product -> {
                    BranchProduct config = configs.get(product.getId());
                    BigDecimal priceOverride = config == null ? null : config.getPriceOverride();
                    return new MenuCacheResponse.AvailabilityItem(product.getId(), product.getName(),
                            config == null ? product.isAvailable() : config.isAvailable(),
                            priceOverride == null ? product.getPrice() : priceOverride, priceOverride);
                })
                .toList();
        return new MenuCacheResponse.BranchAvailability(availabilityItems);
    }

    @Cacheable(cacheNames = CacheConfig.CATEGORIES, key = "'tenant:' + #tenantId")
    @Transactional(readOnly = true)
    public CategoryResponse.ListResponse categoryList(Long tenantId) {
        List<CategoryResponse> rows = categories
                .findAllByTenantIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(tenantId).stream()
                .map(CatalogCacheService::toCategoryResponse)
                .toList();
        return new CategoryResponse.ListResponse(rows);
    }

    @Cacheable(cacheNames = CacheConfig.CATEGORY_DETAILS,
            key = "'tenant:' + #tenantId + ':category:' + #categoryId", unless = "#result == null")
    @Transactional(readOnly = true)
    public CategoryResponse category(Long tenantId, Long categoryId) {
        return categories.findByIdAndTenantId(categoryId, tenantId)
                .filter(category -> category.getDeletedAt() == null)
                .map(CatalogCacheService::toCategoryResponse)
                .orElse(null);
    }

    @Cacheable(cacheNames = CacheConfig.BRANCH_DETAILS,
            key = "'tenant:' + #tenantId + ':branch:' + #branchId", unless = "#result == null")
    @Transactional(readOnly = true)
    public BranchResponse branch(Long tenantId, Long branchId) {
        return branches.findByIdAndTenantIdAndDeletedAtIsNull(branchId, tenantId)
                .map(CatalogCacheService::toBranchResponse)
                .orElse(null);
    }

    @Cacheable(cacheNames = CacheConfig.BRANCH_HOURS,
            key = "'tenant:' + #tenantId + ':branch:' + #branchId")
    @Transactional(readOnly = true)
    public BranchHoursResponse hours(Long tenantId, Long branchId) {
        List<BranchHoursResponse.BranchHour> rows = branchHours.findAllByBranchIdOrderByWeekday(branchId).stream()
                .map(CatalogCacheService::toBranchHour)
                .toList();
        return new BranchHoursResponse(rows);
    }

    private static BigDecimal effectivePrice(Product product, BranchProduct config) {
        return config != null && config.getPriceOverride() != null
                ? config.getPriceOverride() : product.getPrice();
    }

    private static CategoryResponse toCategoryResponse(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getTenantId(),
                category.getSortOrder(), category.isActive(), category.getCreatedAt(),
                category.getUpdatedAt(), category.getDeletedAt());
    }

    private static BranchResponse toBranchResponse(Restaurant branch) {
        return new BranchResponse(branch.getId(), branch.getName(), branch.getAddress(), branch.getPhone(),
                branch.getTenantId(), branch.getCode(), branch.getTimezone(), branch.isActive(),
                branch.getVersion(), branch.getDeletedAt(), branch.getCreatedAt());
    }

    private static BranchHoursResponse.BranchHour toBranchHour(BranchHours hours) {
        return new BranchHoursResponse.BranchHour(hours.getId(), hours.getBranchId(), hours.getWeekday(),
                hours.getOpenTime(), hours.getCloseTime(), hours.isClosed());
    }
}
