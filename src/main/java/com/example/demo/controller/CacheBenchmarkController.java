package com.example.demo.controller;

import com.example.demo.model.BrandVO;
import com.example.demo.service.cache.AfterRedisReadWriteService;
import com.example.demo.service.cache.BrandCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 主从读写分离测试控制器
 */
@Tag(name = "2. Redis 主从读写分离高频缓存", description = "演示高频只读缓存（品牌/系列）在 Lettuce ReadFrom 下的从节点读分流与主节点写淘汰")
@RestController
@RequestMapping("/api/cache")
public class CacheBenchmarkController {

    private final BrandCacheService beforeRedisCacheService;
    private final AfterRedisReadWriteService afterRedisReadWriteService;

    public CacheBenchmarkController(
            @Qualifier("beforeRedisCacheService") BrandCacheService beforeRedisCacheService,
            AfterRedisReadWriteService afterRedisReadWriteService) {
        this.beforeRedisCacheService = beforeRedisCacheService;
        this.afterRedisReadWriteService = afterRedisReadWriteService;
    }

    /**
     * 查询 Redis 主从读写分离策略配置信息
     */
    @Operation(summary = "查询读写分离拓扑策略信息", description = "查看当前 Lettuce 客户端 ReadFrom 策略、主从路由节点及高频保护数据")
    @GetMapping("/info")
    public Map<String, Object> getStrategyInfo() {
        return afterRedisReadWriteService.getSeparationStrategyInfo();
    }

    /**
     * 优化前：单节点/主节点读
     */
    @Operation(summary = "【优化前】常规单节点读取品牌缓存", description = "所有 GET 读命令由主节点处理，流量高峰时主节点读带宽与 CPU 压力极大")
    @GetMapping("/before/get")
    public Map<String, Object> getBefore(
            @Parameter(description = "品牌ID", example = "501") @RequestParam(defaultValue = "501") Long brandId) {
        long start = System.currentTimeMillis();
        BrandVO brand = beforeRedisCacheService.getBrandById(brandId);
        List<String> series = beforeRedisCacheService.getSeriesListByBrandId(brandId);
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> resp = new HashMap<>();
        resp.put("mode", "优化前：读写全部由主节点处理");
        resp.put("routedTo", "主节点（127.0.0.1:6379）");
        resp.put("costMs", cost);
        resp.put("brand", brand);
        resp.put("series", series);
        return resp;
    }

    /**
     * 优化后：从节点读分流
     */
    @Operation(summary = "【优化后】主从读写分离读分流", description = "基于 Lettuce ReadFrom.REPLICA_PREFERRED，读请求自动分流到从节点")
    @GetMapping("/after/get")
    public Map<String, Object> getAfter(
            @Parameter(description = "品牌ID", example = "501") @RequestParam(defaultValue = "501") Long brandId) {
        long start = System.currentTimeMillis();
        BrandVO brand = afterRedisReadWriteService.getBrandById(brandId);
        List<String> series = afterRedisReadWriteService.getSeriesListByBrandId(brandId);
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> resp = new HashMap<>();
        resp.put("mode", "优化后：基于 Lettuce ReadFrom.REPLICA_PREFERRED 主从读写分离");
        resp.put("routedTo", "从节点（127.0.0.1:6380 / 6381）分担读请求");
        resp.put("costMs", cost);
        resp.put("brand", brand);
        resp.put("series", series);
        return resp;
    }

    /**
     * 优化后：写操作与缓存失效，保证走主节点
     */
    @Operation(summary = "【优化后】写操作与缓存失效", description = "更新品牌数据并使缓存失效，写命令由 Lettuce 路由至主节点保证一致性")
    @PostMapping("/after/update")
    public Map<String, Object> updateBrand(@RequestBody BrandVO brandVO) {
        afterRedisReadWriteService.updateBrand(brandVO);

        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "品牌信息已更新，主节点上的缓存已安全失效");
        resp.put("writeRoutedTo", "主节点（127.0.0.1:6379）");
        resp.put("brandId", brandVO.getBrandId());
        return resp;
    }
}
