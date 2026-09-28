package com.example.demo.service;

import com.example.demo.repository.ProductStore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 商城读详情：跨实例共享一个 productId 键，互斥回源并二次检查缓存。 */
@Service
public class ProductCacheService {
    private static final String NULL_MARKER = "__NULL__";
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final ProductStore store;
    private final ObjectMapper json;

    public ProductCacheService(StringRedisTemplate redis, ProductStore store, ObjectMapper json) {
        this.redis = redis; this.store = store; this.json = json;
    }

    public static String cacheKey(long id) { return "mall:product:detail:" + id; }
    private static String lockKey(long id) { return "mall:product:lock:" + id; }

    public ProductStore.Product get(long id) {
        // 非法商品 ID 直接拒绝，不进入缓存和回源流程。
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品 ID 必须为正数");
        // 同一商品的请求使用同一个缓存键；命中时无需加锁。
        String key = cacheKey(id);
        String cached = redis.opsForValue().get(key);
        //如果从 Redis 读到了缓存，就把缓存内容转换成商品对象并立即返回。
        if (cached != null) return decode(cached);

        // 缓存未命中后，最多尝试 20 轮：争取商品锁，或等待其他请求回填缓存。
        for (int attempt = 0; attempt < 20; attempt++) {
            // 锁键按商品 ID 区分；Redis SET NX 只允许同一商品的一个请求拿到锁。
            // owner 是本次持锁令牌；拿不到锁时为 null。
            String owner = tryLock(id);
            if (owner != null) {
                try {
                    // 二次检查：从首次查缓存到拿锁期间，前一位持锁者可能已完成回填。
                    cached = redis.opsForValue().get(key);
                    if (cached != null) return decode(cached);
                    // 只有持锁且二次检查仍未命中的请求才访问 ERP。
                    ProductStore.Product product = store.find(id);
                    // 存在的商品缓存 2 分钟；不存在的商品用空值标记缓存 15 秒，避免反复回源。
                    redis.opsForValue().set(key, product == null ? NULL_MARKER : encode(product),
                            product == null ? Duration.ofSeconds(15) : Duration.ofMinutes(2));
                    return product;
                } finally {
                    // 即使读取或回填失败，也只释放属于当前请求的锁。
                    unlock(id, owner);
                }
            }
            // 未拿到锁时不回源，先查看持锁请求是否已写入缓存。
            cached = redis.opsForValue().get(key);
            if (cached != null) return decode(cached);
            // 缓存尚未出现，短暂等待后重试；中断时保留中断标记并退出。
            try { Thread.sleep(25); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
        }
        // 多轮等待后仍未读到缓存，也未能完成回源，通知调用方稍后重试。
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "商品缓存正在重建，请稍后重试");
    }

    // SET NX PX/EX 的锁附带租期，进程崩溃后不会永久卡住；实际生产需使租期覆盖最长回源时间。

    /**
     *这个方法是在尝试获取某个商品的 Redis 锁；它只尝试一次，不会在方法内等待。
     * 以商品 10200 为例：
     * - lockKey(id) 生成锁键 mall:product:lock:10200。
     * - owner 是本次请求独有的随机令牌，作为锁的值。
     * - setIfAbsent(...) 在锁键不存在时才写入，并设置 10 秒过期时间。写入成功表示拿到锁；键已存在表示其他请求持有锁。
     * - Boolean.TRUE.equals(...) 只有结果明确为 true 才算成功；结果为 false 或 null 都算失败。
     * - 成功就返回 owner，失败返回 null。调用方通过 owner != null 判断自己能否回源。
     * 返回令牌还有一个用途：解锁时会先比较 Redis 中的值是否仍是这个 owner，避免删除别人后来取得的锁。
     */
    public String tryLock(long id) {
        String owner = UUID.randomUUID().toString();
        //Boolean.TRUE.equals(x) 的意思是：只有 x 确实是 true，结果才是 true。
        /**
         * redis.opsForValue().setIfAbsent(
         *     lockKey(id),             // 第 1 个参数：Redis 键
         *     owner,                   // 第 2 个参数：要写入的值
         *     Duration.ofSeconds(10)   // 第 3 个参数：过期时间
         * )
         */
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey(id), owner, Duration.ofSeconds(10))) ? owner : null;
    }

    public void unlock(long id, String owner) {
        // 比较令牌后再删除，防止误删已过期且被别人重新获得的锁。
        redis.execute(UNLOCK, List.of(lockKey(id)), owner);
    }

    public String encode(ProductStore.Product product) {
        try { return json.writeValueAsString(product); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("商品数据编码失败", ex); }
    }

    private ProductStore.Product decode(String value) {
        if (NULL_MARKER.equals(value)) return null;
        try { return json.readValue(value, ProductStore.Product.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("缓存中的商品数据无效", ex); }
    }
}
