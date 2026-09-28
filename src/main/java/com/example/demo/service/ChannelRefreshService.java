package com.example.demo.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 按商品互斥删除详情缓存；下次普通查询负责回源重建。 */
@Service
public class ChannelRefreshService {
    private final StringRedisTemplate redis;
    private final ProductCacheService cache;

    public ChannelRefreshService(StringRedisTemplate redis, ProductCacheService cache) {
        this.redis = redis;
        this.cache = cache;
    }

    public enum RefreshResult { DELETED, BUSY }

    public RefreshResult refresh(long id) {
        String owner = cache.tryLock(id);
        if (owner == null) return RefreshResult.BUSY;
        try {
            redis.delete(ProductCacheService.cacheKey(id));
            return RefreshResult.DELETED;
        } finally {
            cache.unlock(id, owner);
        }
    }
}
