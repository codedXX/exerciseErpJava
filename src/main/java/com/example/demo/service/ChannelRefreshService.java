package com.example.demo.service;

import com.example.demo.repository.ProductStore;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;

/** ERP/商城详情刷新协调：版本阻止旧任务覆盖新数据，锁阻止同时回源。 */
@Service
public class ChannelRefreshService {
    private static final DefaultRedisScript<Long> SWAP = new DefaultRedisScript<>("""
        -- 版本检查、替换缓存、版本递增作为一个 Redis 原子操作。
        local current = tonumber(redis.call('GET', KEYS[1]) or '0')
        if current ~= tonumber(ARGV[1]) then return -1 end
        redis.call('SET', KEYS[2], ARGV[2], 'EX', 120)
        return redis.call('INCR', KEYS[1])
        """, Long.class);
    private final StringRedisTemplate redis;
    private final ProductStore store;
    private final ProductCacheService cache;
    public ChannelRefreshService(StringRedisTemplate redis, ProductStore store, ProductCacheService cache) {
        this.redis = redis; this.store = store; this.cache = cache;
    }
    public enum RefreshResult { UPDATED, BUSY, CONFLICT, NOT_FOUND }
    public record RefreshResponse(RefreshResult result, long version) {}
    private static String versionKey(long id) { return "mall:product:version:" + id; }
    public long version(long id) {
        String value = redis.opsForValue().get(versionKey(id));
        return value == null ? 0 : Long.parseLong(value);
    }
    public RefreshResult refresh(long id, long expectedVersion) {
        String owner = cache.tryLock(id);
        if (owner == null) return RefreshResult.BUSY;
        try {
            // 拿锁后检查版本；过期的并发刷新任务不能覆盖新版本。
            if (version(id) != expectedVersion) return RefreshResult.CONFLICT;
            ProductStore.Product product = store.find(id);
            if (product == null) return RefreshResult.NOT_FOUND;
            // 缓存替换与版本递增在一段 Lua 中完成，不经历删除缓存的空窗。
            Long result = redis.execute(SWAP, List.of(versionKey(id), ProductCacheService.cacheKey(id)),
                    String.valueOf(expectedVersion), cache.encode(product));
            if (result == null) throw new IllegalStateException("Redis refresh unavailable");
            return result == -1 ? RefreshResult.CONFLICT : RefreshResult.UPDATED;
        } finally { cache.unlock(id, owner); }
    }
}
