package com.example.demo;

import com.example.demo.service.OrderRateLimiter;
import org.redisson.api.RedissonClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "demo.redis.read-write-split.enabled=false")
class OrderRateLimiterRedisTest {
    @Autowired OrderRateLimiter limiter;
    @Autowired RedissonClient redisson;

    @Test
    void oldestRequestLeavingTheSlidingWindowFreesOneSlot() throws InterruptedException {
        String userId = Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        String key = OrderRateLimiter.windowKey(userId, "submit");
        try {
            assertTrue(limiter.acquire(userId, "submit").allowed());
            Thread.sleep(5100);
            assertTrue(limiter.acquire(userId, "submit").allowed());
            OrderRateLimiter.Decision rejected = limiter.acquire(userId, "submit");
            assertFalse(rejected.allowed());
            assertTrue(rejected.retryAfterMs() > 0);
            Thread.sleep(5100);
            assertTrue(limiter.acquire(userId, "submit").allowed());
        } finally {
            redisson.getRateLimiter(key).delete();
        }
    }

    @Test
    void concurrentCallsOnTwoServicesAllowOnlyTwo() {
        String userId = Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        String key = OrderRateLimiter.windowKey(userId, "submit");
        OrderRateLimiter secondInstance = new OrderRateLimiter(redisson);
        try {
            var calls = IntStream.range(0, 20)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() ->
                            (i % 2 == 0 ? limiter : secondInstance).acquire(userId, "submit").allowed()))
                    .toList();
            assertEquals(2, calls.stream().filter(CompletableFuture::join).count());
        } finally {
            redisson.getRateLimiter(key).delete();
        }
    }
}
