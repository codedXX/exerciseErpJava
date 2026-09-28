package com.example.demo.service.cache;

import com.example.demo.model.BrandVO;
import io.lettuce.core.ReadFrom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 【优化后实现】基于 Lettuce 读写分离（ReadFrom.REPLICA_PREFERRED）的高频缓存架构
 *
 * 核心设计与收益：
 * 1. 读请求自动分流：高频访问的品牌详情、商品系列数据走只读从节点（6380 / 6381），卸去主节点的读负载；
 * 2. 写操作与失效由主节点处理：所有的 SET、DEL、EXPIRE 等命令由 Lettuce 自动识别并发往主节点（6379）；
 * 3. 故障容灾与自动回退：采用 REPLICA_PREFERRED 策略。若从节点全部不可用，读请求自动回退至主节点；
 * 4. 读写分离一致性保证：采用标准的旁路缓存模式（先更新数据库，再删除主节点缓存，从节点随后同步失效）。
 */
@Service("afterRedisReadWriteService")
public class AfterRedisReadWriteService implements BrandCacheService {
    private static final Logger log = LoggerFactory.getLogger(AfterRedisReadWriteService.class);

    private static final String BRAND_KEY_PREFIX = "erp:brand:";
    private static final String SERIES_KEY_PREFIX = "erp:brand:series:";

    // 模拟持久化数据库（MySQL）
    private final ConcurrentHashMap<Long, BrandVO> mockDb = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private RedisConnectionFactory redisConnectionFactory;

    public AfterRedisReadWriteService() {
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
        // 核心机制：Lettuce 拦截到 GET 命令，匹配 ReadFrom.REPLICA_PREFERRED 策略，自动从从节点连接池中轮询分流
        log.info("【优化后-从节点读分流】查询品牌缓存，缓存键：{}，Lettuce 自动路由至从节点 127.0.0.1:6380 / 6381", cacheKey);

        if (redisTemplate != null) {
            try {
                Object cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached instanceof BrandVO brandVO) {
                    log.info("【优化后-从节点命中】成功从从节点获取品牌数据：{}", brandVO.getBrandName());
                    return brandVO;
                }
            } catch (Exception e) {
                log.warn("从库读取异常，Lettuce 会自动尝试重试/回退: {}", e.getMessage());
            }
        }

        // 缓存未命中，查询数据库并回填
        BrandVO brandFromDb = mockDb.get(brandId);
        if (brandFromDb != null && redisTemplate != null) {
            try {
                // 回填缓存（SET 命令为写操作，Lettuce 自动路由至主节点）
                log.info("【优化后-回填缓存】回填数据，SET 命令由 Lettuce 自动路由至主节点 127.0.0.1:6379");
                redisTemplate.opsForValue().set(cacheKey, brandFromDb, 2, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("写入主节点异常：{}", e.getMessage());
            }
        }
        return brandFromDb;
    }

    @Override
    public List<String> getSeriesListByBrandId(Long brandId) {
        String cacheKey = SERIES_KEY_PREFIX + brandId;
        log.info("【优化后-从节点读分流】查询商品系列，缓存键：{}，路由至从节点", cacheKey);

        if (redisTemplate != null) {
            try {
                Object cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> list = (List<String>) cached;
                    return list;
                }
            } catch (Exception e) {
                log.warn("从库读取系列异常: {}", e.getMessage());
            }
        }

        BrandVO brandVO = mockDb.get(brandId);
        List<String> seriesList = brandVO != null ? brandVO.getSeriesList() : List.of();
        if (redisTemplate != null) {
            try {
                log.info("【优化后-回填缓存】写入商品系列，SET 命令路由至主节点");
                redisTemplate.opsForValue().set(cacheKey, seriesList, 2, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("写入主节点异常：{}", e.getMessage());
            }
        }
        return seriesList;
    }

    @Override
    public void updateBrand(BrandVO brandVO) {
        log.info("【优化后-写操作】更新品牌基础信息，品牌 ID：{}", brandVO.getBrandId());
        // 1. 更新数据库（写事务）
        mockDb.put(brandVO.getBrandId(), brandVO);

        // 2. 缓存失效与淘汰（DEL 命令发送至主节点，再由主节点同步给各从节点）
        evictBrandCache(brandVO.getBrandId());
    }

    @Override
    public void evictBrandCache(Long brandId) {
        String brandKey = BRAND_KEY_PREFIX + brandId;
        String seriesKey = SERIES_KEY_PREFIX + brandId;
        log.info("【优化后-淘汰缓存】执行 DEL 失效命令，Lettuce 将写入和删除命令路由至主节点 127.0.0.1:6379");

        if (redisTemplate != null) {
            try {
                // DEL 命令属于写操作，由主节点处理
                redisTemplate.delete(List.of(brandKey, seriesKey));
                log.info("【优化后-淘汰缓存】主节点已删除缓存，后续从节点同步失效：{}、{}", brandKey, seriesKey);
            } catch (Exception e) {
                log.warn("主节点淘汰缓存异常：{}", e.getMessage());
            }
        }
    }

    /**
     * 获取当前读写分离策略配置信息
     */
    public Map<String, Object> getSeparationStrategyInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("strategy", "REPLICA_PREFERRED");
        info.put("description", "读请求优先分流到从节点；写入和删除命令由主节点处理；从节点不可用时自动回退到主节点");
        info.put("readRouting", "从节点（例如 127.0.0.1:6380、127.0.0.1:6381）");
        info.put("writeRouting", "主节点（127.0.0.1:6379）");
        info.put("protectedData", List.of("品牌详情缓存 (erp:brand:*)", "商品系列缓存 (erp:brand:series:*)"));
        return info;
    }
}
