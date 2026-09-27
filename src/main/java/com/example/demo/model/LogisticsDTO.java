package com.example.demo.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 发货/物流信息
 */
@Schema(description = "物流微服务：发货轨迹与物流数据")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsDTO implements Serializable {

    @Schema(description = "快递运单号", example = "SF10928374829")
    private String trackingNumber;

    @Schema(description = "快递承运公司", example = "顺丰速运")
    private String expressCompany;

    @Schema(description = "收货详细地址", example = "北京市海淀区中关村南大街 1 号")
    private String shippingAddress;

    @Schema(description = "物流状态", example = "运输中")
    private String status;

    @Schema(description = "发货时间")
    private LocalDateTime deliveryTime;
}
