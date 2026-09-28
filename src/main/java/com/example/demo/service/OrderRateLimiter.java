package com.example.demo.service;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 按用户和接口分别限流，多个实例共享下单额度。 */
@Service
public class OrderRateLimiter {
    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final Duration IDLE_EXPIRY = Duration.ofMinutes(1);
    private final RedissonClient redisson;

    public OrderRateLimiter(RedissonClient redisson) {
        this.redisson = redisson;
    }

    public record Decision(boolean allowed, long remaining, long retryAfterMs) {}

    public static String windowKey(String userId, String api) {
        return "mall:order:limiter:" + userId + ":" + api;
    }

    public Decision acquire(String userId, String api) {
        // 按“用户 ID + 接口”生成 Redis 键，为这组请求取得同一个限流器。
        RRateLimiter limiter = redisson.getRateLimiter(windowKey(userId, api));

        // 首次设置限流规则：所有应用实例共享额度，每 10 秒最多 2 次；闲置后自动过期。
        // trySetRate 不会覆盖已经存在的规则。
        limiter.trySetRate(RateType.OVERALL, 2, WINDOW, IDLE_EXPIRY);

        // 非阻塞地申请一次额度；额度用尽时立即拒绝。
        if (!limiter.tryAcquire()) {
            // Redisson 不提供精确的下次可用时间，这里返回保守的 10 秒重试提示。
            return new Decision(false, 0, WINDOW.toMillis());
        }

        // 申请成功；剩余额度是读取时的快照，并发请求可能随即消耗它。
        return new Decision(true, limiter.availablePermits(), 0);
    }
}
