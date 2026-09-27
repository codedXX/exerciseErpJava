package com.example.demo.controller;

import com.example.demo.repository.ProductStore;
import com.example.demo.service.ChannelRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Tag(name = "3.3 商品强制刷新", description = "模拟 ERP 主数据更新、版本查询及持锁原子刷新缓存")
@RestController
@RequestMapping("/api/demo")
public class ProductRefreshController {
    private final ProductStore store;
    private final ChannelRefreshService refresh;

    public ProductRefreshController(ProductStore store, ChannelRefreshService refresh) {
        this.store = store;
        this.refresh = refresh;
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

    @Operation(summary = "查询商品缓存版本", description = "刷新前先读取版本，作为 expectedVersion 传给强制刷新接口。")
    @GetMapping("/products/{id}/version")
    public Map<String, Object> version(@Parameter(description = "商品 ID", example = "10200") @PathVariable long id) {
        return Map.of("productId", id, "version", refresh.version(id));
    }

    @Operation(summary = "演示请求头强制刷新商品详情", description = "在请求头填 X-Demo-Refresh: true，并带上当前 expectedVersion。持锁回源后原子替换缓存并递增版本。此请求头仅用于本地功能演示，不是身份认证。")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "刷新完成"),
            @ApiResponse(responseCode = "401", description = "未提供演示刷新请求头"),
            @ApiResponse(responseCode = "409", description = "商品版本已变化"),
            @ApiResponse(responseCode = "423", description = "同一商品正在刷新")})
    @PostMapping("/products/{id}/refresh")
    public ResponseEntity<?> refresh(@Parameter(description = "商品 ID", example = "10200") @PathVariable long id,
                                     @Parameter(description = "刷新前查询到的版本", schema = @Schema(type = "string", example = "0")) @RequestParam long expectedVersion,
                                     @Parameter(description = "本地演示刷新开关，填 true", example = "true")
                                     @RequestHeader(value = "X-Demo-Refresh", required = false) String demoRefresh) {
        if (!"true".equalsIgnoreCase(demoRefresh))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Demo-Refresh must be true");
        if (id <= 0 || expectedVersion < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid version or productId");
        ChannelRefreshService.RefreshResult result = refresh.refresh(id, expectedVersion);
        HttpStatus status = switch (result) {
            case UPDATED -> HttpStatus.OK;
            case BUSY -> HttpStatus.LOCKED;
            case CONFLICT -> HttpStatus.CONFLICT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).body(Map.of("result", result.name(), "version", refresh.version(id)));
    }
}
