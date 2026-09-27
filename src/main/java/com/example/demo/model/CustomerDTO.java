package com.example.demo.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 客户/会员信息
 */
@Schema(description = "客户微服务：客户与会员数据")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDTO implements Serializable {

    @Schema(description = "客户ID", example = "8888")
    private Long customerId;

    @Schema(description = "客户姓名", example = "张三")
    private String customerName;

    @Schema(description = "会员等级", example = "VIP3")
    private String memberLevel;

    @Schema(description = "联系电话", example = "13800001234")
    private String phone;
}
