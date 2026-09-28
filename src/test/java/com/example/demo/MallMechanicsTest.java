package com.example.demo;

import com.example.demo.repository.ProductStore;
import com.example.demo.service.ProductCacheService;
import com.example.demo.service.OrderRateLimiter;
import com.example.demo.service.ChannelRefreshService;
import com.example.demo.service.ProductTrafficLimiter;
import com.example.demo.service.RefreshAuthorization;
import com.example.demo.controller.OrderSubmitController;
import com.example.demo.controller.ProductRefreshController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MallMechanicsTest {
    @Test
    void sameProductIdIsSharedAcrossChannels() {
        assertEquals("mall:product:detail:10200", ProductCacheService.cacheKey(10200));
        assertEquals("mall:product:detail:10200", ProductCacheService.cacheKey(10200));
    }

    @Test
    void orderWindowIsScopedByUserAndApi() {
        assertEquals("mall:order:limiter:42:submit", OrderRateLimiter.windowKey("42", "submit"));
        assertNotEquals(OrderRateLimiter.windowKey("42", "submit"), OrderRateLimiter.windowKey("43", "submit"));
    }

    @Test
    void refreshDeletesOnlyTheProductCacheWhileHoldingItsLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ProductCacheService cache = mock(ProductCacheService.class);
        ChannelRefreshService service = new ChannelRefreshService(redis, cache);
        when(cache.tryLock(10200)).thenReturn("owner");
        assertEquals(ChannelRefreshService.RefreshResult.DELETED, service.refresh(10200));
        verify(redis).delete("mall:product:detail:10200");
        verify(cache).unlock(10200, "owner");
    }

    @Test
    void refreshReturnsBusyWithoutDeletingCacheWhenProductLockIsHeld() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ProductCacheService cache = mock(ProductCacheService.class);
        ChannelRefreshService service = new ChannelRefreshService(redis, cache);
        assertEquals(ChannelRefreshService.RefreshResult.BUSY, service.refresh(10200));
        verifyNoInteractions(redis);
    }

    @Test
    void repeatedDetailReadUsesSharedCacheInsteadOfRepeatedErpRead() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        Map<String, String> data = new ConcurrentHashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> data.get(call.getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call ->
                data.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        ProductStore store = new ProductStore();
        ProductCacheService cache = new ProductCacheService(redis, store, new ObjectMapper());
        assertEquals("美容营养商品", cache.get(10200).name());
        assertEquals("美容营养商品", cache.get(10200).name());
        assertEquals(1, store.readCount());
    }

    @Test
    void thirdSubmissionReturns429WithoutCreatingAnOrder() {
        OrderRateLimiter limiter = mock(OrderRateLimiter.class);
        when(limiter.acquire("42", "submit"))
                .thenReturn(new OrderRateLimiter.Decision(true, 1, 0),
                        new OrderRateLimiter.Decision(true, 0, 0),
                        new OrderRateLimiter.Decision(false, 0, 5000));
        OrderSubmitController controller = new OrderSubmitController(limiter);
        assertEquals(HttpStatus.OK, controller.submit("42").getStatusCode());
        assertEquals(HttpStatus.OK, controller.submit("42").getStatusCode());
        ResponseEntity<?> third = controller.submit("42");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getStatusCode());
        assertEquals("5", third.getHeaders().getFirst("Retry-After"));
        assertEquals(Map.of("error", "RATE_LIMITED", "message", "下单请求过于频繁，请稍后重试", "retryAfterMs", 5000L), third.getBody());
    }

    @Test
    void refreshWithoutBearerHeaderReturns401() {
        ChannelRefreshService refresh = mock(ChannelRefreshService.class);
        ProductTrafficLimiter limiter = mock(ProductTrafficLimiter.class);
        ProductRefreshController controller = new ProductRefreshController(new ProductStore(), refresh,
                limiter, new RefreshAuthorization("test-secret"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.refresh(10200, null));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        verifyNoInteractions(refresh);
        verifyNoInteractions(limiter);
    }

    @Test
    void validBearerAllowsRefresh() {
        ChannelRefreshService refresh = mock(ChannelRefreshService.class);
        ProductTrafficLimiter limiter = mock(ProductTrafficLimiter.class);
        when(limiter.allowRefresh(10200)).thenReturn(true);
        when(refresh.refresh(10200)).thenReturn(ChannelRefreshService.RefreshResult.DELETED);
        ProductRefreshController controller = new ProductRefreshController(new ProductStore(), refresh,
                limiter, new RefreshAuthorization("test-secret"));
        ResponseEntity<?> response = controller.refresh(10200, "Bearer test-secret");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("result", "DELETED", "message", "商品缓存已删除"), response.getBody());
    }

    @Test
    void busyRefreshReturnsChineseMessage() {
        ChannelRefreshService refresh = mock(ChannelRefreshService.class);
        ProductTrafficLimiter limiter = mock(ProductTrafficLimiter.class);
        when(limiter.allowRefresh(10200)).thenReturn(true);
        when(refresh.refresh(10200)).thenReturn(ChannelRefreshService.RefreshResult.BUSY);
        ProductRefreshController controller = new ProductRefreshController(new ProductStore(), refresh,
                limiter, new RefreshAuthorization("test-secret"));

        ResponseEntity<?> response = controller.refresh(10200, "Bearer test-secret");
        assertEquals(HttpStatus.LOCKED, response.getStatusCode());
        assertEquals(Map.of("result", "BUSY", "message", "该商品正在刷新，请稍后重试"), response.getBody());
    }
}
