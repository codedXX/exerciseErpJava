package com.example.demo;

import com.example.demo.model.BrandVO;
import com.example.demo.service.cache.AfterRedisReadWriteService;
import com.example.demo.service.cache.BeforeRedisCacheService;
import io.lettuce.core.ReadFrom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class RedisReadWriteSeparationTest {
    private static final Logger log = LoggerFactory.getLogger(RedisReadWriteSeparationTest.class);

    @Autowired
    private AfterRedisReadWriteService afterRedisReadWriteService;

    @Autowired
    private BeforeRedisCacheService beforeRedisCacheService;

    @Autowired
    private LettuceClientConfigurationBuilderCustomizer customizer;

    @Test
    @DisplayName("验证 Lettuce ReadFrom 策略配置正确性")
    void testLettuceReadFromConfiguration() {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder();
        customizer.customize(builder);
        LettuceClientConfiguration config = builder.build();

        // 验证读写分离策略为 REPLICA_PREFERRED
        assertTrue(config.getReadFrom().isPresent(), "Lettuce 客户端应显式配置 ReadFrom 策略");
        assertEquals(ReadFrom.REPLICA_PREFERRED, config.getReadFrom().get(),
                "读策略必须为 REPLICA_PREFERRED，优先读取从节点分流");
        log.info("【测试通过】Lettuce 读写分离配置已生效: {}", config.getReadFrom().get());
    }

    @Test
    @DisplayName("测试高频品牌与系列数据查询业务逻辑与回退机制")
    void testBrandCacheReadWriteFlow() {
        Long brandId = 501L;

        // 1. 测试优化后高频读取（从库读）
        BrandVO brandVO = afterRedisReadWriteService.getBrandById(brandId);
        assertNotNull(brandVO);
        assertTrue(brandVO.getBrandName().contains("Sony 索尼"));

        List<String> series = afterRedisReadWriteService.getSeriesListByBrandId(brandId);
        assertNotNull(series);
        assertFalse(series.isEmpty());
        log.info("【优化后查询结果】获取到品牌: {}, 系列列表: {}", brandVO.getBrandName(), series);

        // 2. 测试优化后写操作与失效（主库写）
        BrandVO updated = BrandVO.builder()
                .brandId(brandId)
                .brandName("Sony 索尼 (已更新)")
                .logoUrl("https://img.example.com/sony_new.png")
                .description("更新后的索尼描述")
                .seriesList(List.of("WH-1000XM5 系列", "Alpha 微单系列"))
                .build();
        afterRedisReadWriteService.updateBrand(updated);

        // 再次查询验证缓存淘汰后回源正确
        BrandVO reloaded = afterRedisReadWriteService.getBrandById(brandId);
        assertEquals("Sony 索尼 (已更新)", reloaded.getBrandName());

        // 3. 验证策略信息
        Map<String, Object> strategyInfo = afterRedisReadWriteService.getSeparationStrategyInfo();
        assertEquals("REPLICA_PREFERRED", strategyInfo.get("strategy"));
    }
}
