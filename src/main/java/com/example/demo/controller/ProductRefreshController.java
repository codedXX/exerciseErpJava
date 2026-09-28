package com.example.demo.controller;

import com.example.demo.repository.ProductStore;
import com.example.demo.service.ChannelRefreshService;
import com.example.demo.service.ProductTrafficLimiter;
import com.example.demo.service.RefreshAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Tag(name = "3.4 商品强制刷新", description = "刷新鉴权、独立限流及持锁删除商品缓存")
@RestController
@RequestMapping("/api/demo")
public class ProductRefreshController {
    private final ProductStore store;
    private final ChannelRefreshService refresh;
    private final ProductTrafficLimiter limiter;
    private final RefreshAuthorization authorization;

    public ProductRefreshController(ProductStore store, ChannelRefreshService refresh,
                                    ProductTrafficLimiter limiter, RefreshAuthorization authorization) {
        this.store = store;
        this.refresh = refresh;
        this.limiter = limiter;
        this.authorization = authorization;
    }

    @Operation(summary = "模拟 ERP 修改商品主数据", description = "只修改模拟主数据，不主动删除商城缓存；可随后调用演示刷新接口观察新旧数据切换。")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "主数据已修改"),
            @ApiResponse(responseCode = "400", description = "商品参数无效")})
    @PutMapping("/erp/products/{id}")
    public ProductStore.Product update(@Parameter(description = "商品 ID", example = "10200") @PathVariable long id,
                                       @RequestBody ProductInput input) {
        if (id <= 0 || input.name() == null || input.name().isBlank() || input.priceCents() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid product");
        ProductStore.Product product = new ProductStore.Product(id, input.name(), input.priceCents());
        store.save(product);
        return product;
    }

    public record ProductInput(
            @Schema(description = "商品名称", example = "ERP更新后的商品") String name,
            @Schema(description = "价格，单位：分", example = "20900") int priceCents) {
    }

    @Operation(summary = "经鉴权与独立限流后强制刷新商品详情", description = "Authorization: Bearer <token>；持锁删除缓存，下一次普通查询回源重建。")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "缓存已删除"),
            @ApiResponse(responseCode = "401", description = "刷新令牌无效"),
            @ApiResponse(responseCode = "429", description = "刷新频率超限"),
            @ApiResponse(responseCode = "423", description = "同一商品正在刷新")})
    @PostMapping("/channel/products/{id}/refresh")
    public ResponseEntity<?> refresh(@Parameter(description = "商品 ID", example = "10200") @PathVariable long id,
                                     @Parameter(description = "刷新令牌", example = "Bearer <token>")
                                     @RequestHeader(value = "Authorization", required = false) String bearer) {
        authorization.require(bearer);
        if (id <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid productId");
        if (!limiter.allowRefresh(id))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "product refresh rate exceeded");
        ChannelRefreshService.RefreshResult result = refresh.refresh(id);
        HttpStatus status = switch (result) {
            case DELETED -> HttpStatus.OK;
            case BUSY -> HttpStatus.LOCKED;
        };
        return ResponseEntity.status(status).body(Map.of("result", result.name()));
    }
}
