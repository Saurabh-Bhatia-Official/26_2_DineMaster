package com.dinemaster.cache;

import com.dinemaster.config.CacheConfig;
import com.dinemaster.restaurant.repository.RestaurantRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CacheInvalidationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidationService.class);

    private final CacheManager cacheManager;
    private final RestaurantRepository branches;

    public CacheInvalidationService(CacheManager cacheManager, RestaurantRepository branches) {
        this.cacheManager = cacheManager;
        this.branches = branches;
    }

    public void invalidateBranchCatalog(Long tenantId, Long branchId) {
        if (tenantId == null || branchId == null) return;
        afterCommit(() -> evictBranchCatalog(tenantId, branchId));
    }

    public void invalidateTenantCatalog(Long tenantId, Long changedCategoryId) {
        invalidateTenantCatalog(tenantId,
                changedCategoryId == null ? List.of() : List.of(changedCategoryId));
    }

    public void invalidateTenantCatalog(Long tenantId, List<Long> changedCategoryIds) {
        if (tenantId == null) return;
        afterCommit(() -> {
            evict(CacheConfig.CATEGORIES, tenantKey(tenantId));
            changedCategoryIds.stream().distinct()
                    .forEach(categoryId -> evict(CacheConfig.CATEGORY_DETAILS, categoryKey(tenantId, categoryId)));
            try {
                branches.findAllByTenantIdAndDeletedAtIsNullOrderById(tenantId).stream()
                        .map(branch -> branch.getId())
                        .forEach(branchId -> evictBranchCatalog(tenantId, branchId));
            } catch (DataAccessException exception) {
                LOGGER.warn("Could not enumerate branches to invalidate tenant {} catalog caches; they will expire by TTL",
                        tenantId, exception);
            }
        });
    }

    private void evictBranchCatalog(Long tenantId, Long branchId) {
        String key = branchKey(tenantId, branchId);
        evict(CacheConfig.BRANCH_CATALOG, key);
        evict(CacheConfig.GUEST_MENU, key);
        evict(CacheConfig.BRANCH_AVAILABILITY, key);
    }

    public void invalidateBranchDetails(Long tenantId, Long branchId) {
        if (tenantId == null || branchId == null) return;
        afterCommit(() -> evict(CacheConfig.BRANCH_DETAILS, branchKey(tenantId, branchId)));
    }

    public void invalidateBranchHours(Long tenantId, Long branchId) {
        if (tenantId == null || branchId == null) return;
        afterCommit(() -> evict(CacheConfig.BRANCH_HOURS, branchKey(tenantId, branchId)));
    }

    private void afterCommit(Runnable invalidation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidation.run();
                }
            });
        } else {
            invalidation.run();
        }
    }

    private void evict(String cacheName, String key) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                LOGGER.warn("Cache '{}' is not configured; cannot evict key '{}'", cacheName, key);
                return;
            }
            cache.evict(key);
        } catch (RuntimeException exception) {
            LOGGER.warn("Cache eviction failed for cache '{}'; stale data will expire by TTL", cacheName, exception);
        }
    }

    private static String tenantKey(Long tenantId) {
        return "tenant:" + tenantId;
    }

    private static String categoryKey(Long tenantId, Long categoryId) {
        return "tenant:" + tenantId + ":category:" + categoryId;
    }

    private static String branchKey(Long tenantId, Long branchId) {
        return "tenant:" + tenantId + ":branch:" + branchId;
    }
}
