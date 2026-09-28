package com.example.demo.controller;

import com.example.demo.repository.ProductStore;
import com.example.demo.service.ProductCacheService;
import com.example.demo.service.ProductTrafficLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Tag(name = "3.3 商品渠道普通访问", description = "独立查询入口，与缓存优化入口共用普通访问额度")
@RestController
@RequestMapping("/api/demo/channel/products")
public class ProductChannelController {
    private final ProductStore store;
    private final ProductCacheService cache;
    private final ProductTrafficLimiter limiter;

    public ProductChannelController(ProductStore store, ProductCacheService cache, ProductTrafficLimiter limiter) {
        this.store = store;
        this.cache = cache;
        this.limiter = limiter;
    }

    @Operation(summary = "渠道普通访问商品详情（独立入口，共享普通查询额度）")
    @GetMapping("/{id}")
    public Map<String, Object> product(@PathVariable long id) {
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品 ID 必须为正数");
        if (!limiter.allowRead(id)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "商品读取请求过于频繁");
        ProductStore.Product product = cache.get(id);
        if (product == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        return Map.of("product", product, "dbReadCount", store.readCount(), "cacheKey", ProductCacheService.cacheKey(id));
    }
}
