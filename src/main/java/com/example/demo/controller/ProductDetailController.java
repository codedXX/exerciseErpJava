package com.example.demo.controller;

import com.example.demo.repository.ProductStore;
import com.example.demo.service.ProductCacheService;
import com.example.demo.service.ProductTrafficLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Tag(name = "3.1 商品详情缓存", description = "按商品 ID 共享缓存，未命中时互斥回源并二次检查")
@RestController
@RequestMapping("/api/demo")
public class ProductDetailController {
    private final ProductStore store;
    private final ProductCacheService cache;
    private final ProductTrafficLimiter limiter;

    public ProductDetailController(ProductStore store, ProductCacheService cache, ProductTrafficLimiter limiter) {
        this.store = store;
        this.cache = cache;
        this.limiter = limiter;
    }

    @Operation(summary = "查询商品详情（共享缓存）", description = "PC、移动端等渠道统一按商品 ID 读取 Redis；未命中时互斥回源并二次检查缓存。响应中的 dbReadCount 用于观察模拟 ERP 的读取次数。")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "商品详情"),
            @ApiResponse(responseCode = "404", description = "商品不存在"),
            @ApiResponse(responseCode = "503", description = "缓存正在重建，等待超时")})
    @GetMapping("/products/{id}")
    public Map<String, Object> product(@Parameter(description = "商品 ID", example = "10200") @PathVariable long id) {
        if (id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品 ID 必须为正数");
        if (!limiter.allowRead(id)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "商品读取请求过于频繁");
        ProductStore.Product product = cache.get(id);
        if (product == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        return Map.of("product", product, "dbReadCount", store.readCount(), "cacheKey", ProductCacheService.cacheKey(id));
    }
}
