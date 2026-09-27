package com.example.demo.controller;

import com.example.demo.model.OrderDetailVO;
import com.example.demo.service.order.OrderDetailQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 订单查询性能对比控制器
 */
@Tag(name = "1. 订单详情性能优化测试", description = "演示串行调用 (~1s) 与线程池 Future 并行组装 (~300-400ms) 的性能对比")
@RestController
@RequestMapping("/api/order")
public class OrderBenchmarkController {

    private final OrderDetailQueryService beforeSerialOrderService;
    private final OrderDetailQueryService afterParallelOrderService;

    public OrderBenchmarkController(
            @Qualifier("beforeSerialOrderService") OrderDetailQueryService beforeSerialOrderService,
            @Qualifier("afterParallelOrderService") OrderDetailQueryService afterParallelOrderService) {
        this.beforeSerialOrderService = beforeSerialOrderService;
        this.afterParallelOrderService = afterParallelOrderService;
    }

    /**
     * 优化前接口：串行调用
     * 预期耗时：~1000ms
     */
    @Operation(summary = "【优化前】串行查询订单详情", description = "依次串行调用客户(300ms)、商品(400ms)、物流(300ms)，总耗时累加约 1000ms")
    @GetMapping("/before")
    public OrderDetailVO getOrderDetailBefore(
            @Parameter(description = "订单ID", example = "1001") @RequestParam(defaultValue = "1001") Long orderId) {
        return beforeSerialOrderService.getOrderDetail(orderId);
    }

    /**
     * 优化后接口：线程池 + CompletableFuture 并行调用
     * 预期耗时：~300ms ~ 400ms
     */
    @Operation(summary = "【优化后】并行查询订单详情", description = "通过自定义线程池与 CompletableFuture 并发调度，总耗时降至约 300~400ms")
    @GetMapping("/after")
    public OrderDetailVO getOrderDetailAfter(
            @Parameter(description = "订单ID", example = "1001") @RequestParam(defaultValue = "1001") Long orderId) {
        return afterParallelOrderService.getOrderDetail(orderId);
    }

    /**
     * 对比基准测试接口：一次性调用优化前与优化后，并输出耗时与性能提升比
     */
    @Operation(summary = "【一键对比】串行 vs 并行性能基准测试", description = "一次性调用串行与并行两种实现，自动对比耗时差、降低比例与提速倍数")
    @GetMapping("/compare")
    public Map<String, Object> comparePerformance(
            @Parameter(description = "订单ID", example = "1001") @RequestParam(defaultValue = "1001") Long orderId) {
        OrderDetailVO beforeResult = beforeSerialOrderService.getOrderDetail(orderId);
        OrderDetailVO afterResult = afterParallelOrderService.getOrderDetail(orderId);

        long beforeCost = beforeResult.getQueryCostTimeMs();
        long afterCost = afterResult.getQueryCostTimeMs();
        double speedup = ((double) (beforeCost - afterCost) / beforeCost) * 100;

        Map<String, Object> report = new HashMap<>();
        report.put("orderId", orderId);
        report.put("beforeSerialCostMs", beforeCost);
        report.put("afterParallelCostMs", afterCost);
        report.put("performanceImprovement", String.format("耗时降低约 %.1f%%, 性能提升约 %.2f 倍", speedup, (double) beforeCost / afterCost));
        report.put("summary", "接口耗时由约 1000ms 成功降低至约 " + afterCost + "ms");
        report.put("sampleData", afterResult);
        return report;
    }
}
