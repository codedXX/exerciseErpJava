package com.example.demo.rpc;

import com.example.demo.model.CustomerDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 模拟客户微服务 / 远程 RPC 接口
 */
@Service
public class CustomerRpcService {
    private static final Logger log = LoggerFactory.getLogger(CustomerRpcService.class);

    public CustomerDTO getCustomerInfo(Long customerId) {
        log.info("[CustomerRpcService] 开始查询客户信息，客户 ID：{}，当前线程：{}", customerId, Thread.currentThread().getName());
        try {
            // 模拟 RPC 网络 I/O 与业务查询耗时 300ms
            TimeUnit.MILLISECONDS.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("客户 RPC 查询被中断", e);
        }
        log.info("[CustomerRpcService] 查询客户信息完成");
        return CustomerDTO.builder()
                .customerId(customerId)
                .customerName("张三")
                .memberLevel("VIP3")
                .phone("13800001234")
                .build();
    }
}
