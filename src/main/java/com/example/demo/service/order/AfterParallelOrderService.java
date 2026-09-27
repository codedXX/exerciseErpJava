package com.example.demo.service.order;

import com.example.demo.model.CustomerDTO;
import com.example.demo.model.LogisticsDTO;
import com.example.demo.model.OrderDetailVO;
import com.example.demo.model.ProductItemDTO;
import com.example.demo.rpc.CustomerRpcService;
import com.example.demo.rpc.LogisticsRpcService;
import com.example.demo.rpc.ProductRpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 【优化后实现】通过 ExecutorService 线程池提交任务并使用 Future 汇总组装
 * 
 * 核心优化原理：
 * 1. 并发调度：使用 executorService.submit() 将 3 个互不依赖的远程子服务异步并行执行；
 * 2. 耗时收益：总耗时由串行累加（300ms + 400ms + 300ms ≈ 1000ms）降至以最慢任务为准的约 300~400ms；
 * 3. 熔断与超时控制：通过 future.get(800, TimeUnit.MILLISECONDS) 显式指定超时阈值，超时或异常时安全柔性降级，防止整个接口被单点拖死。
 */
@Service("afterParallelOrderService")
public class AfterParallelOrderService implements OrderDetailQueryService {
    private static final Logger log = LoggerFactory.getLogger(AfterParallelOrderService.class);

    private final CustomerRpcService customerRpcService;
    private final ProductRpcService productRpcService;
    private final LogisticsRpcService logisticsRpcService;
    private final ExecutorService executorService;

    @Autowired(required = false)
    private RestTemplate restTemplate;

    public AfterParallelOrderService(
            CustomerRpcService customerRpcService,
            ProductRpcService productRpcService,
            LogisticsRpcService logisticsRpcService,
            @Qualifier("orderDetailThreadPool") ExecutorService executorService) {
        this.customerRpcService = customerRpcService;
        this.productRpcService = productRpcService;
        this.logisticsRpcService = logisticsRpcService;
        this.executorService = executorService;
    }

    @Override
    public OrderDetailVO getOrderDetail(Long orderId) {
        long startTime = System.currentTimeMillis();
        log.info("【优化后-并行查询】通过 ExecutorService.submit 异步并发提交任务，orderId: {}", orderId);

        // 1. 初始化本地订单基本数据
        Long customerId = 8888L;
        OrderDetailVO orderDetail = OrderDetailVO.builder()
                .orderId(orderId)
                .orderSn("ORD202609220001")
                .totalAmount(new BigDecimal("1068.00"))
                .orderStatus("PAID")
                .createTime(LocalDateTime.now().minusDays(1))
                .build();

        // 2. 线程池并行提交任务 1：查询客户信息（耗时约 300ms）
        Future<CustomerDTO> customerFuture = executorService.submit(() -> {
            log.info("线程 [{}] 正在并行执行客户信息查询...", Thread.currentThread().getName());
            // 若生产环境使用 RestTemplate 远程 HTTP 调用，可写为：
            // return restTemplate.getForObject("http://customer-service/customer/{id}", CustomerDTO.class, customerId);
            return customerRpcService.getCustomerInfo(customerId);
        });

        // 3. 线程池并行提交任务 2：查询订单商品明细列表（耗时约 400ms）
        Future<List<ProductItemDTO>> productFuture = executorService.submit(() -> {
            log.info("线程 [{}] 正在并行执行商品明细查询...", Thread.currentThread().getName());
            // 若生产环境使用 RestTemplate 远程 HTTP 调用，可写为：
            // return restTemplate.getForObject("http://product-service/products/{orderId}", List.class, orderId);
            return productRpcService.getProductItemsByOrderId(orderId);
        });

        // 4. 线程池并行提交任务 3：查询发货物流信息（耗时约 300ms）
        Future<LogisticsDTO> logisticsFuture = executorService.submit(() -> {
            log.info("线程 [{}] 正在并行执行发货物流查询...", Thread.currentThread().getName());
            // 若生产环境使用 RestTemplate 远程 HTTP 调用，可写为：
            // return restTemplate.getForObject("http://logistics-service/shipment/{orderId}", LogisticsDTO.class, orderId);
            return logisticsRpcService.getLogisticsByOrderId(orderId);
        });

        // 5. 汇总等待各子任务结果：通过 future.get() 限制等待上限（800ms），超时或异常时进行柔性降级

        // (1) 组装客户信息
        try {
            CustomerDTO customer = customerFuture.get(800, TimeUnit.MILLISECONDS);
            orderDetail.setCustomerInfo(customer);
        } catch (TimeoutException e) {
            log.warn("【客户服务超时】查询客户信息超过 800ms，执行柔性降级");
            customerFuture.cancel(true); // 取消未完成任务，节约系统资源
            orderDetail.setCustomerInfo(CustomerDTO.builder().customerId(customerId).customerName("默认客户(超时降级)").memberLevel("普通会员").phone("--").build());
        } catch (Exception e) {
            log.error("【客户服务异常】查询失败: {}", e.getMessage());
            orderDetail.setCustomerInfo(CustomerDTO.builder().customerId(customerId).customerName("默认客户(异常降级)").memberLevel("普通会员").phone("--").build());
        }

        // (2) 组装商品明细
        try {
            List<ProductItemDTO> products = productFuture.get(800, TimeUnit.MILLISECONDS);
            orderDetail.setProductItems(products);
        } catch (TimeoutException e) {
            log.warn("【商品服务超时】查询商品列表超过 800ms，执行柔性降级");
            productFuture.cancel(true);
            orderDetail.setProductItems(Collections.emptyList());
        } catch (Exception e) {
            log.error("【商品服务异常】查询失败: {}", e.getMessage());
            orderDetail.setProductItems(Collections.emptyList());
        }

        // (3) 组装物流信息
        try {
            LogisticsDTO logistics = logisticsFuture.get(800, TimeUnit.MILLISECONDS);
            orderDetail.setLogisticsInfo(logistics);
        } catch (TimeoutException e) {
            log.warn("【物流服务超时】查询物流信息超过 800ms，执行柔性降级");
            logisticsFuture.cancel(true);
            orderDetail.setLogisticsInfo(LogisticsDTO.builder().trackingNumber("--").expressCompany("查询超时").shippingAddress("--").status("物流信息暂不可用").build());
        } catch (Exception e) {
            log.error("【物流服务异常】查询失败: {}", e.getMessage());
            orderDetail.setLogisticsInfo(LogisticsDTO.builder().trackingNumber("--").expressCompany("顺丰速运").shippingAddress("--").status("处理中").build());
        }

        // 6. 统计总耗时：等于耗时最长子任务耗时 max(300ms, 400ms, 300ms) ≈ 400ms
        long costTime = System.currentTimeMillis() - startTime;
        orderDetail.setQueryCostTimeMs(costTime);
        log.info("【优化后-并行查询】全部 Future 汇聚组装完成，总耗时: {} ms (对比优化前 ~1000ms，降幅达 60% 左右)", costTime);

        return orderDetail;
    }
}
