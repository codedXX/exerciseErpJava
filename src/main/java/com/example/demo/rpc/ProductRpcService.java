package com.example.demo.rpc;

import com.example.demo.model.ProductItemDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 模拟商品中心微服务 / 远程 RPC 接口
 */
@Service
public class ProductRpcService {
    private static final Logger log = LoggerFactory.getLogger(ProductRpcService.class);

    public List<ProductItemDTO> getProductItemsByOrderId(Long orderId) {
        log.info("[ProductRpcService] 开始查询订单商品列表，订单 ID：{}，当前线程：{}", orderId, Thread.currentThread().getName());
        try {
            // 模拟 RPC 网络 I/O 与业务查询耗时 400ms
            TimeUnit.MILLISECONDS.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("商品 RPC 查询被中断", e);
        }
        log.info("[ProductRpcService] 查询订单商品列表完成");
        return List.of(
                ProductItemDTO.builder()
                        .skuId(1001L)
                        .skuName("智能降噪蓝牙耳机")
                        .brandId(501L)
                        .brandName("Sony 索尼")
                        .price(new BigDecimal("999.00"))
                        .quantity(1)
                        .build(),
                ProductItemDTO.builder()
                        .skuId(1002L)
                        .skuName("原装耳机保护壳")
                        .brandId(501L)
                        .brandName("Sony 索尼")
                        .price(new BigDecimal("69.00"))
                        .quantity(1)
                        .build()
        );
    }
}
