package com.example.demo.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Redis 滑动窗口：同一用户、同一接口，任意连续 10 秒内最多提交 2 次。 */
@Service
public class OrderRateLimiter {
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setResultType(List.class);
        SCRIPT.setScriptText("""
                local key = KEYS[1]
                local time = redis.call('TIME')
                local now = time[1] * 1000 + math.floor(time[2] / 1000)

                -- 删除 10 秒窗口之外的请求
                redis.call('ZREMRANGEBYSCORE', key, '-inf', now - 10000)
                local count = redis.call('ZCARD', key)
                if count < 2 then
                    -- UUID 保证同一毫秒的两次请求不会覆盖彼此
                    redis.call('ZADD', key, now, ARGV[1])
                    redis.call('PEXPIRE', key, 10000)
                    return {1, 1 - count, 0}
                end

                -- 最早的一次请求离开窗口后，才可以重试
                local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                return {0, 0, tonumber(oldest[2]) + 10000 - now}
                """);
    }

    private final StringRedisTemplate redis;

    public OrderRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Decision(boolean allowed, long remaining, long retryAfterMs) {}

    public static String windowKey(String userId, String api) {
        return "mall:order:window:" + userId + ":" + api;
    }

    public Decision acquire(String userId, String api) {
        String key = windowKey(userId, api);
        List<?> result = redis.execute(SCRIPT, List.of(key), UUID.randomUUID().toString());
        if (result == null || result.size() != 3) {
            throw new IllegalStateException("Redis 限流结果无效");
        }
        return new Decision(number(result, 0) == 1, number(result, 1), number(result, 2));
    }

    private static long number(List<?> result, int index) {
        return ((Number) result.get(index)).longValue();
    }
}
