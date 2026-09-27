package com.example.demo;

import com.example.demo.model.OrderDetailVO;
import com.example.demo.service.order.AfterParallelOrderService;
import com.example.demo.service.order.BeforeSerialOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class OrderOptimizationTest {
    private static final Logger log = LoggerFactory.getLogger(OrderOptimizationTest.class);

    @Autowired
    private BeforeSerialOrderService beforeSerialOrderService;

    @Autowired
    private AfterParallelOrderService afterParallelOrderService;

    @Test
    @DisplayName("测试对比：订单详情查询串行 vs 并行性能耗时")
    void testOrderQueryOptimization() {
        Long orderId = 1001L;

        // 1. 优化前：串行调用 (Customer 300ms + Product 400ms + Logistics 300ms ≈ 1000ms)
        log.info("========== 开始测试优化前：串行调用 ==========");
        OrderDetailVO beforeVo = beforeSerialOrderService.getOrderDetail(orderId);
        long beforeTime = beforeVo.getQueryCostTimeMs();
        log.info(">>> 优化前-串行总耗时: {} ms", beforeTime);

        assertNotNull(beforeVo.getCustomerInfo());
        assertNotNull(beforeVo.getProductItems());
        assertNotNull(beforeVo.getLogisticsInfo());
        assertTrue(beforeTime >= 950, "串行耗时应不少于各子服务耗时之和(~1000ms)");

        // 2. 优化后：线程池 + CompletableFuture 并行调用 (耗时取最大值 max(300, 400, 300) ≈ 400ms)
        log.info("========== 开始测试优化后：并行调用 ==========");
        OrderDetailVO afterVo = afterParallelOrderService.getOrderDetail(orderId);
        long afterTime = afterVo.getQueryCostTimeMs();
        log.info(">>> 优化后-并行总耗时: {} ms", afterTime);

        assertNotNull(afterVo.getCustomerInfo());
        assertNotNull(afterVo.getProductItems());
        assertNotNull(afterVo.getLogisticsInfo());
        assertTrue(afterTime < 650, "并行耗时应显著降低至最大任务耗时附近(~400ms)");

        // 3. 性能提升计算
        double reductionRate = ((double) (beforeTime - afterTime) / beforeTime) * 100;
        log.info("========== 性能对比结果 ==========");
        log.info("串行耗时: {} ms -> 并行耗时: {} ms", beforeTime, afterTime);
        log.info("接口耗时降低: {:.2f}%, 性能提升: {:.2f} 倍", reductionRate, (double) beforeTime / afterTime);

        assertTrue(beforeTime > afterTime, "优化后的耗时必须明显优于串行耗时");
    }
}
