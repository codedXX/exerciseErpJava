package com.example.demo.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 品牌与商品系列等高频缓存数据
 */
@Schema(description = "品牌与高频商品系列视图对象")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandVO implements Serializable {

    @Schema(description = "品牌ID", example = "501")
    private Long brandId;

    @Schema(description = "品牌名称", example = "Sony 索尼")
    private String brandName;

    @Schema(description = "品牌Logo图片链接", example = "https://img.example.com/sony.png")
    private String logoUrl;

    @Schema(description = "品牌简要描述", example = "索尼株式会社，视听与数码领军品牌")
    private String description;

    @Schema(description = "旗下高频商品系列名称列表")
    private List<String> seriesList;
}
