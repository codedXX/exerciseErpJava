package com.example.demo;

import com.example.demo.controller.ProductChannelController;
import com.example.demo.controller.ProductDetailController;
import com.example.demo.controller.ProductRefreshController;
import com.example.demo.repository.ProductStore;
import com.example.demo.service.ChannelRefreshService;
import com.example.demo.service.ProductCacheService;
import com.example.demo.service.ProductTrafficLimiter;
import com.example.demo.service.RefreshAuthorization;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductTrafficTest {
    @Test
    void bothReadEntrypointsShareTheOrdinaryReadLimit() {
        ProductCacheService cache = mock(ProductCacheService.class);
        ProductTrafficLimiter limiter = mock(ProductTrafficLimiter.class);
        ProductStore store = new ProductStore();
        when(cache.get(10200)).thenReturn(new ProductStore.Product(10200, "sample", 100));
        when(limiter.allowRead(10200)).thenReturn(true, false);

        ProductDetailController optimized = new ProductDetailController(store, cache, limiter);
        ProductChannelController channel = new ProductChannelController(store, cache, limiter);
        assertEquals("sample", ((ProductStore.Product) optimized.product(10200).get("product")).name());
        ResponseStatusException rejected = assertThrows(ResponseStatusException.class, () -> channel.product(10200));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
        verify(cache, times(1)).get(10200);
    }

    @Test
    void refreshRequiresConfiguredBearerSecretBeforeAnyRedisAction() {
        ChannelRefreshService refresh = mock(ChannelRefreshService.class);
        ProductTrafficLimiter limiter = mock(ProductTrafficLimiter.class);
        ProductRefreshController controller = new ProductRefreshController(new ProductStore(), refresh,
                limiter, new RefreshAuthorization("secret-value"));

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> controller.refresh(10200, "Bearer wrong"));
        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatusCode());
        verifyNoInteractions(refresh, limiter);
    }

    @Test
    void refreshHasItsOwnStricterLimitBeforeDeletingCache() {
        ChannelRefreshService refresh = mock(ChannelRefreshService.class);
        ProductTrafficLimiter limiter = mock(ProductTrafficLimiter.class);
        when(limiter.allowRefresh(10200)).thenReturn(true, false);
        when(refresh.refresh(10200)).thenReturn(ChannelRefreshService.RefreshResult.DELETED);
        ProductRefreshController controller = new ProductRefreshController(new ProductStore(), refresh,
                limiter, new RefreshAuthorization("secret-value"));

        assertEquals(HttpStatus.OK, controller.refresh(10200, "Bearer secret-value").getStatusCode());
        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> controller.refresh(10200, "Bearer secret-value"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
        verify(refresh, times(1)).refresh(10200);
    }

    @Test
    void unsetRefreshSecretFailsClosed() {
        RefreshAuthorization authorization = new RefreshAuthorization("");
        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> authorization.require("Bearer anything"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.getStatusCode());
    }

    @Test
    void productReadQuotaRejectsHotIdWithoutSpendingGlobalQuota() {
        RedissonClient redisson = mock(RedissonClient.class);
        RRateLimiter productQuota = mock(RRateLimiter.class);
        RRateLimiter globalQuota = mock(RRateLimiter.class);
        when(redisson.getRateLimiter("mall:product:rate:read:10200:v1")).thenReturn(productQuota);
        when(redisson.getRateLimiter("mall:product:rate:read:global:v1")).thenReturn(globalQuota);
        when(globalQuota.tryAcquire()).thenReturn(true);
        ProductTrafficLimiter limiter = new ProductTrafficLimiter(redisson, 500, 100, 20);

        assertFalse(limiter.allowRead(10200));
        verify(redisson, never()).getRateLimiter("mall:product:rate:read:global:v1");
    }
}
