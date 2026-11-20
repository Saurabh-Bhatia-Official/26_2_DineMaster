package com.dinemaster.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dinemaster.catalog.dto.MenuCacheResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@ActiveProfiles("test")
class CacheConfigTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheErrorHandler cacheErrorHandler;

    @Test
    void menuCacheDtoRoundTripsThroughJsonWithoutEntityTypes() {
        MenuCacheResponse expected = new MenuCacheResponse(
                List.of(new MenuCacheResponse.Category(1L, "Lunch", 2,
                        List.of(new MenuCacheResponse.MenuItem(3L, "Soup", null,
                                new BigDecimal("10.00"), new BigDecimal("5.00"), null)))));
        Jackson2JsonRedisSerializer<MenuCacheResponse> serializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, MenuCacheResponse.class);

        MenuCacheResponse actual = serializer.deserialize(serializer.serialize(expected));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void redisReadFailureIsHandledSoCacheableMethodCanFallBackToDatabase() {
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn(CacheConfig.BRANCH_CATALOG);

        assertThatCode(() -> cacheErrorHandler.handleCacheGetError(
                new IllegalStateException("Redis unavailable"), cache, "tenant:1:branch:2"))
                .doesNotThrowAnyException();
    }
}
