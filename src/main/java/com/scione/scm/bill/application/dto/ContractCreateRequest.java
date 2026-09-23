package com.scione.scm.bill.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 手动创建合同请求（支持手动补充领星缺失的字段）。
 */
@Data
@Schema(description = "手动创建合同请求")
public class ContractCreateRequest {

    @Schema(description = "采购单号", example = "PO20261201001", required = true)
    private String purchaseOrderNo;

    // ========== 供方信息（可选，覆盖领星数据） ==========

    @Schema(description = "供方名称（可选，不传则使用领星数据）", example = "浙江XXX供应商有限公司")
    private String supplierName;

    @Schema(description = "供方地址（可选，领星暂无此字段）", example = "浙江省杭州市西湖区XX路XX号")
    private String supplierAddress;

    @Schema(description = "供方联系人（可选，不传则使用领星数据）", example = "张三")
    private String contactPerson;

    @Schema(description = "供方电话（可选，不传则使用领星数据）", example = "13800138000")
    private String supplierPhone;

    // ========== 需方信息（可选，覆盖默认需方公司数据） ==========

    @Schema(description = "需方名称（可选，不传则使用默认需方公司）", example = "上海宋艳科技有限公司")
    private String buyerCompanyName;

    @Schema(description = "需方地址（可选）", example = "上海市浦东新区XX路XX号")
    private String buyerAddress;

    @Schema(description = "需方邮编（可选）", example = "200000")
    private String postCode;

    @Schema(description = "需方电话（可选）", example = "021-12345678")
    private String buyerPhone;

    @Schema(description = "需方传真（可选）", example = "021-87654321")
    private String fax;

    // ========== 合同金额信息（可选，覆盖领星数据） ==========

    @Schema(description = "预付款比例（可选，如 0.3 表示30%）", example = "0.0")
    private BigDecimal prepaymentRatio;

    @Schema(description = "结算方式（可选）", example = "交付即结")
    private String paymentMethod;

    @Schema(description = "签署日期（可选，格式 yyyy-MM-dd，不传则使用今天）", example = "2026-12-01")
    private String contractDate;

    @Schema(description = "交货日期（可选，格式 yyyy-MM-dd）", example = "2026-12-15")
    private String deliveryDate;

    @Schema(description = "合同总金额（可选，不传则使用领星采购单金额）", example = "10500.00")
    private BigDecimal contractAmount;
}