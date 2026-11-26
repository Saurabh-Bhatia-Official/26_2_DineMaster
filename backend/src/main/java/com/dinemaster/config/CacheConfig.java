package com.dinemaster.config;

import com.dinemaster.catalog.dto.CategoryResponse;
import com.dinemaster.catalog.dto.MenuCacheResponse;
import com.dinemaster.restaurant.dto.BranchHoursResponse;
import com.dinemaster.restaurant.dto.BranchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig implements CachingConfigurer {
    public static final String BRANCH_CATALOG = "branch-catalog";
    public static final String GUEST_MENU = "guest-menu";
    public static final String BRANCH_AVAILABILITY = "branch-availability";
    public static final String CATEGORIES = "categories";
    public static final String CATEGORY_DETAILS = "category-details";
    public static final String BRANCH_DETAILS = "branch-details";
    public static final String BRANCH_HOURS = "branch-hours";

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    @Profile("!test")
    CacheManager redisCacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()));

        Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
                BRANCH_CATALOG, configuration(defaults, objectMapper, MenuCacheResponse.class, Duration.ofMinutes(2)),
                GUEST_MENU, configuration(defaults, objectMapper, MenuCacheResponse.GuestMenu.class, Duration.ofMinutes(2)),
                BRANCH_AVAILABILITY, configuration(defaults, objectMapper,
                        MenuCacheResponse.BranchAvailability.class, Duration.ofMinutes(2)),
                CATEGORIES, configuration(defaults, objectMapper, CategoryResponse.ListResponse.class, Duration.ofMinutes(10)),
                CATEGORY_DETAILS, configuration(defaults, objectMapper, CategoryResponse.class, Duration.ofMinutes(10)),
                BRANCH_DETAILS, configuration(defaults, objectMapper, BranchResponse.class, Duration.ofMinutes(10)),
                BRANCH_HOURS, configuration(defaults, objectMapper, BranchHoursResponse.class, Duration.ofMinutes(10)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults.entryTtl(Duration.ofMinutes(2)))
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    @Bean
    @Profile("test")
    CacheManager testCacheManager() {
        return new ConcurrentMapCacheManager(
                BRANCH_CATALOG, GUEST_MENU, BRANCH_AVAILABILITY, CATEGORIES, CATEGORY_DETAILS,
                BRANCH_DETAILS, BRANCH_HOURS);
    }

    @Bean
    CacheErrorHandler cacheErrorHandler() {
        return loggingCacheErrorHandler();
    }

    @Override
    public CacheManager cacheManager() {
        return null;
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return loggingCacheErrorHandler();
    }

    private static CacheErrorHandler loggingCacheErrorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                LOGGER.warn("Redis cache read failed for cache '{}'; continuing with the database", cache.getName(), exception);
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                LOGGER.warn("Redis cache write failed for cache '{}'; serving the database result", cache.getName(), exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                LOGGER.warn("Redis cache eviction failed for cache '{}'; stale data will expire by TTL", cache.getName(), exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                LOGGER.warn("Redis cache clear failed for cache '{}'", cache.getName(), exception);
            }
        };
    }

    private static <T> RedisCacheConfiguration configuration(RedisCacheConfiguration base,
            ObjectMapper objectMapper, Class<T> valueType, Duration ttl) {
        Jackson2JsonRedisSerializer<T> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, valueType);
        return base.entryTtl(ttl).serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(serializer));
    }
}
