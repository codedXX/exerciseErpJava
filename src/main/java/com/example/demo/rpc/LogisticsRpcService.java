package com.example.demo.rpc;

import com.example.demo.model.LogisticsDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 模拟物流/发货微服务 / 远程 RPC 接口
 */
@Service
public class LogisticsRpcService {
    private static final Logger log = LoggerFactory.getLogger(LogisticsRpcService.class);

    public LogisticsDTO getLogisticsByOrderId(Long orderId) {
        log.info("[LogisticsRpcService] 开始查询发货物流信息，orderId: {}, 当前线程: {}", orderId, Thread.currentThread().getName());
        try {
            // 模拟 RPC 网络 I/O 与业务查询耗时 300ms
            TimeUnit.MILLISECONDS.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Logistics RPC query interrupted", e);
        }
        log.info("[LogisticsRpcService] 查询发货物流信息完成");
        return LogisticsDTO.builder()
                .trackingNumber("SF10928374829")
                .expressCompany("顺丰速运")
                .shippingAddress("北京市海淀区中关村南大街 1 号")
                .status("运输中")
                .deliveryTime(LocalDateTime.now().minusHours(5))
                .build();
    }
}
