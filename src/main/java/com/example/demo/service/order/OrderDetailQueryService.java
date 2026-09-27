package com.example.demo.service.order;

import com.example.demo.model.OrderDetailVO;

public interface OrderDetailQueryService {
    /**
     * 根据订单ID查询订单详情（聚合客户信息、商品明细、物流发货信息）
     *
     * @param orderId 订单ID
     * @return 聚合后的订单详情
     */
    OrderDetailVO getOrderDetail(Long orderId);
}
