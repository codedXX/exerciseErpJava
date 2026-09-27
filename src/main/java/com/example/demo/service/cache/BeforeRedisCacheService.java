package com.example.demo.service.cache;

import com.example.demo.model.BrandVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 【优化前实现】常规 Redis 读写不分离
 * 痛点分析：
 * 1. 品牌、系列等基础数据读请求 QPS 极高（百万级/日），写请求极低；
 * 2. 默认单节点模式或未开启 ReadFrom 的 Lettuce 连接，所有命令（GET/MGET/SET/DEL）均打到 Master 主节点；
 * 3. 结果：Master 节点在流量高峰期网卡带宽打满、CPU 飙升，进而影响核心订单交易的写性能；
 * 4. 从节点（Replica）长期仅作为冷备或数据同步目标，未发挥读分流价值，资源严重浪费。
 */
@Service("beforeRedisCacheService")
public class BeforeRedisCacheService implements BrandCacheService {
    private static final Logger log = LoggerFactory.getLogger(BeforeRedisCacheService.class);

    private static final String BRAND_KEY_PREFIX = "erp:brand:";
    private static final String SERIES_KEY_PREFIX = "erp:brand:series:";

    // 模拟持久化数据库
    private final ConcurrentHashMap<Long, BrandVO> mockDb = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    public BeforeRedisCacheService() {
        // 初始化模拟数据库数据
        mockDb.put(501L, BrandVO.builder()
                .brandId(501L)
                .brandName("Sony 索尼")
                .logoUrl("https://img.example.com/sony.png")
                .description("索尼株式会社，视听与数码领军品牌")
                .seriesList(List.of("WH-1000XM5 系列", "LinkBuds 系列", "PlayStation 系列"))
                .build());
    }

    @Override
    public BrandVO getBrandById(Long brandId) {
        String cacheKey = BRAND_KEY_PREFIX + brandId;
        log.info("【优化前-单节点读】读取品牌缓存，Key: {}，路由目标 -> [Master 节点 127.0.0.1:6379]", cacheKey);

        // 优化前：所有读流量全部由 Master 主库承受
        if (redisTemplate != null) {
            try {
                Object cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached instanceof BrandVO brandVO) {
                    log.info("【优化前-命中缓存】从 Master 节点获取品牌: {}", brandVO.getBrandName());
                    return brandVO;
                }
            } catch (Exception e) {
                log.warn("Redis 读取异常 (Master): {}", e.getMessage());
            }
        }

        // 回源数据库并回填 Master 节点
        BrandVO brandFromDb = mockDb.get(brandId);
        if (brandFromDb != null && redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, brandFromDb, 2, TimeUnit.HOURS);
                log.info("【优化前-回填缓存】写入 Master 节点完成: {}", cacheKey);
            } catch (Exception e) {
                log.warn("Redis 写入异常 (Master): {}", e.getMessage());
            }
        }
        return brandFromDb;
    }

    @Override
    public List<String> getSeriesListByBrandId(Long brandId) {
        String cacheKey = SERIES_KEY_PREFIX + brandId;
        log.info("【优化前-单节点读】读取商品系列缓存，Key: {}，路由目标 -> [Master 节点 127.0.0.1:6379]", cacheKey);

        if (redisTemplate != null) {
            try {
                Object cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> list = (List<String>) cached;
                    return list;
                }
            } catch (Exception e) {
                log.warn("Redis 读取异常 (Master): {}", e.getMessage());
            }
        }

        BrandVO brandVO = mockDb.get(brandId);
        List<String> seriesList = brandVO != null ? brandVO.getSeriesList() : List.of();
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, seriesList, 2, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("Redis 写入异常 (Master): {}", e.getMessage());
            }
        }
        return seriesList;
    }

    @Override
    public void updateBrand(BrandVO brandVO) {
        log.info("【优化前-写操作】更新品牌信息，brandId: {}", brandVO.getBrandId());
        // 1. 更新数据库
        mockDb.put(brandVO.getBrandId(), brandVO);
        // 2. 淘汰 Master 节点缓存
        evictBrandCache(brandVO.getBrandId());
    }

    @Override
    public void evictBrandCache(Long brandId) {
        String brandKey = BRAND_KEY_PREFIX + brandId;
        String seriesKey = SERIES_KEY_PREFIX + brandId;
        log.info("【优化前-失效缓存】淘汰缓存，目标 -> [Master 节点 127.0.0.1:6379], keys: {}, {}", brandKey, seriesKey);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(List.of(brandKey, seriesKey));
            } catch (Exception e) {
                log.warn("Redis 删除异常: {}", e.getMessage());
            }
        }
    }
}
