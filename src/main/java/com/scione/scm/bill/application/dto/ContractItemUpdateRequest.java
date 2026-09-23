package com.scione.scm.bill.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 合同明细修改请求。
 */
@Data
@Schema(description = "合同明细修改请求")
public class ContractItemUpdateRequest {

    @Schema(description = "明细ID（必填，用于定位要修改的明细）", example = "123", required = true)
    private Long id;

    @Schema(description = "数量", example = "100")
    private Integer quantity;

    @Schema(description = "单价", example = "10.50")
    private BigDecimal unitPrice;

    @Schema(description = "金额（禁止手动传入，由数量 × 不含税单价自动计算）", example = "1050.00")
    private BigDecimal amount;

    @Schema(description = "交货日期（格式 yyyy-MM-dd）", example = "2026-12-15")
    private String deliveryDate;

    @Schema(description = "备注", example = "加急订单")
    private String remark;
}
