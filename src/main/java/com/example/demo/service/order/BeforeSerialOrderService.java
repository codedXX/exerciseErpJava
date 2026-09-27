package com.example.demo.service.order;

import com.example.demo.model.CustomerDTO;
import com.example.demo.model.LogisticsDTO;
import com.example.demo.model.OrderDetailVO;
import com.example.demo.model.ProductItemDTO;
import com.example.demo.rpc.CustomerRpcService;
import com.example.demo.rpc.LogisticsRpcService;
import com.example.demo.rpc.ProductRpcService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 【优化前实现】串行调用多微服务查询
 * 问题剖析：
 * 1. 多个远程 RPC 服务互不依赖（客户、商品、发货信息只依赖 orderId/customerId），却按顺序单线程同步阻塞调用；
 * 2. 接口总响应时间为所有下游服务耗时之和：T = T(Customer: 300ms) + T(Product: 400ms) + T(Logistics: 300ms) ≈ 1000ms；
 * 3. 任何一个下游服务响应抖动，都会直接叠加到主接口上，导致接口 P99 严重恶化，容易引发微服务线程阻塞和雪崩。
 */
@Service("beforeSerialOrderService")
@RequiredArgsConstructor
public class BeforeSerialOrderService implements OrderDetailQueryService {
    private static final Logger log = LoggerFactory.getLogger(BeforeSerialOrderService.class);

    private final CustomerRpcService customerRpcService;
    private final ProductRpcService productRpcService;
    private final LogisticsRpcService logisticsRpcService;

    @Override
    public OrderDetailVO getOrderDetail(Long orderId) {
        long startTime = System.currentTimeMillis();
        log.info("【优化前-串行查询】开始聚合订单详情，orderId: {}", orderId);

        // 1. 模拟查询本地订单主表基本信息（例如：10ms）
        Long customerId = 8888L;
        OrderDetailVO orderDetail = OrderDetailVO.builder()
                .orderId(orderId)
                .orderSn("ORD202609220001")
                .totalAmount(new BigDecimal("1068.00"))
                .orderStatus("PAID")
                .createTime(LocalDateTime.now().minusDays(1))
                .build();

        // 2. 串行同步阻塞调用：客户服务（耗时 ~300ms）
        CustomerDTO customerDTO = customerRpcService.getCustomerInfo(customerId);
        orderDetail.setCustomerInfo(customerDTO);

        // 3. 串行同步阻塞调用：商品服务（耗时 ~400ms）
        List<ProductItemDTO> productItems = productRpcService.getProductItemsByOrderId(orderId);
        orderDetail.setProductItems(productItems);

        // 4. 串行同步阻塞调用：物流服务（耗时 ~300ms）
        LogisticsDTO logisticsDTO = logisticsRpcService.getLogisticsByOrderId(orderId);
        orderDetail.setLogisticsInfo(logisticsDTO);

        long costTime = System.currentTimeMillis() - startTime;
        orderDetail.setQueryCostTimeMs(costTime);
        log.info("【优化前-串行查询】订单详情聚合完成，总耗时: {} ms", costTime);

        return orderDetail;
    }
}
