package com.example.demo.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单商品条目信息
 */
@Schema(description = "商品微服务：订单商品项明细")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductItemDTO implements Serializable {

    @Schema(description = "商品规格 ID", example = "1001")
    private Long skuId;

    @Schema(description = "商品名称", example = "智能降噪蓝牙耳机")
    private String skuName;

    @Schema(description = "品牌ID", example = "501")
    private Long brandId;

    @Schema(description = "品牌名称", example = "Sony 索尼")
    private String brandName;

    @Schema(description = "销售单价", example = "999.00")
    private BigDecimal price;

    @Schema(description = "购买数量", example = "1")
    private Integer quantity;
}
