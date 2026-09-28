package com.example.demo.service;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/** 普通查询入口共享 Redis 限流额度，刷新入口使用独立额度。 */
@Service
public class ProductTrafficLimiter {
    private static final Duration IDLE_EXPIRY = Duration.ofMinutes(5);
    private final RedissonClient redisson;
    private final long readGlobalRate;
    private final long readProductRate;
    private final long refreshGlobalRate;

    public ProductTrafficLimiter(RedissonClient redisson,
            @Value("${demo.product-limits.read-global-per-second:500}") long readGlobalRate,
            @Value("${demo.product-limits.read-product-per-second:100}") long readProductRate,
            @Value("${demo.product-limits.refresh-global-per-minute:20}") long refreshGlobalRate) {
        this.redisson = redisson;
        this.readGlobalRate = readGlobalRate;
        this.readProductRate = readProductRate;
        this.refreshGlobalRate = refreshGlobalRate;
    }

    public boolean allowRead(long id) {
        return acquire("mall:product:rate:read:" + id + ":v1", readProductRate, Duration.ofSeconds(1))
                && acquire("mall:product:rate:read:global:v1", readGlobalRate, Duration.ofSeconds(1));
    }

    public boolean allowRefresh(long id) {
        return acquire("mall:product:rate:refresh:" + id + ":v1", 1, Duration.ofSeconds(30))
                && acquire("mall:product:rate:refresh:global:v1", refreshGlobalRate, Duration.ofMinutes(1));
    }

    private boolean acquire(String key, long rate, Duration window) {
        if (rate < 1) throw new IllegalStateException("商品限流额度必须为正数");
        try {
            RRateLimiter limiter = redisson.getRateLimiter(key);
            limiter.trySetRate(RateType.OVERALL, rate, window, IDLE_EXPIRY);
            return limiter.tryAcquire();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "商品限流服务暂不可用", ex);
        }
    }
}
