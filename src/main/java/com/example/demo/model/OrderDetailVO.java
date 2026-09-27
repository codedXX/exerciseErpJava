package com.example.demo.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情聚合视图对象
 */
@Schema(description = "订单详情聚合返回对象")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDetailVO implements Serializable {

    @Schema(description = "订单ID", example = "1001")
    private Long orderId;

    @Schema(description = "订单编号", example = "ORD202609220001")
    private String orderSn;

    @Schema(description = "订单总金额", example = "1068.00")
    private BigDecimal totalAmount;

    @Schema(description = "订单状态", example = "PAID")
    private String orderStatus;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "客户微服务提供：会员与客户信息")
    private CustomerDTO customerInfo;

    @Schema(description = "商品微服务提供：商品条目列表")
    private List<ProductItemDTO> productItems;

    @Schema(description = "物流微服务提供：发货物流与轨迹")
    private LogisticsDTO logisticsInfo;

    @Schema(description = "本次查询总耗时统计 (毫秒)", example = "412")
    private Long queryCostTimeMs;
}
