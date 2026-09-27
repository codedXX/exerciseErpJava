package com.example.demo;

import com.example.demo.repository.ProductStore;
import com.example.demo.service.ProductCacheService;
import com.example.demo.service.OrderRateLimiter;
import com.example.demo.service.ChannelRefreshService;
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
        assertEquals("mall:order:window:42:submit", OrderRateLimiter.windowKey("42", "submit"));
        assertNotEquals(OrderRateLimiter.windowKey("42", "submit"), OrderRateLimiter.windowKey("43", "submit"));
    }

    @Test
    void refreshRejectsOutdatedVersionBeforeReadingProduct() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ProductStore store = mock(ProductStore.class);
        ProductCacheService cache = mock(ProductCacheService.class);
        ChannelRefreshService service = new ChannelRefreshService(redis, store, cache);
        when(cache.tryLock(10200)).thenReturn("owner");
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.opsForValue().get("mall:product:version:10200")).thenReturn("1");
        assertEquals(ChannelRefreshService.RefreshResult.CONFLICT, service.refresh(10200, 0));
        verifyNoInteractions(store);
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
    }

    @Test
    void refreshWithoutDemoHeaderReturns401EvenWithoutConfiguredToken() {
        ChannelRefreshService refresh = mock(ChannelRefreshService.class);
        ProductRefreshController controller = new ProductRefreshController(new ProductStore(), refresh);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.refresh(10200, 0, null));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        verifyNoInteractions(refresh);
    }

    @Test
    void demoHeaderAllowsRefreshWithoutConfiguredToken() {
        ChannelRefreshService refresh = mock(ChannelRefreshService.class);
        when(refresh.refresh(10200, 0)).thenReturn(ChannelRefreshService.RefreshResult.UPDATED);
        when(refresh.version(10200)).thenReturn(1L);
        ProductRefreshController controller = new ProductRefreshController(new ProductStore(), refresh);
        ResponseEntity<?> response = controller.refresh(10200, 0, "true");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("result", "UPDATED", "version", 1L), response.getBody());
    }
}
