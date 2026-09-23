package com.scione.scm.bill.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合同修改请求（所有字段可选，只更新传入的字段）。
 */
@Data
@Schema(description = "合同修改请求")
public class ContractUpdateRequest {

    @Schema(description = "合同编号；修改后会同步更新明细与操作日志中的冗余编号", example = "HT202609301234")
    private String contractNo;

    @Schema(description = "合同名称", example = "采购合同-供应商-PO单号")
    private String contractName;

    @Schema(description = "合同类型：1-采购合同 2-购销合同 3-框架合同 4-人事合同；切换类型时会同时切换到该类型的默认模板", example = "1")
    private Integer contractType;

    @Schema(description = "需方公司ID；选择公司后，名称、地址、信用代码等信息由公司档案带入", example = "27")
    private Long buyerCompanyId;

    // ========== 供方信息 ==========

    @Schema(description = "供方名称", example = "浙江XXX供应商有限公司")
    private String supplierName;

    @Schema(description = "供方地址", example = "浙江省杭州市西湖区XX路XX号")
    private String supplierAddress;

    @Schema(description = "供方联系人", example = "张三")
    private String contactPerson;

    @Schema(description = "供方电话", example = "13800138000")
    private String supplierPhone;

    @Schema(description = "供方统一社会信用代码", example = "913100001234567890")
    private String supplierCreditCode;

    @Schema(description = "供方收款人")
    private String supplierAccountName;

    @Schema(description = "供方银行账号", example = "6222021000000000000")
    private String supplierBankAccount;

    @Schema(description = "供方开户行", example = "中国工商银行")
    private String supplierBankName;

    @Schema(description = "预付款比例", example = "0.3")
    private String prepayPercent;

    @Schema(description = "结算方式：现结、月结、其他", example = "月结")
    @Pattern(regexp = "现结|月结|其他", message = "结算方式只能为现结、月结、其他")
    private String settlementMethod;

    // ========== 需方信息 ==========

    @Schema(description = "需方名称", example = "上海宋艳科技有限公司")
    private String buyerCompanyName;

    @Schema(description = "需方地址", example = "上海市浦东新区XX路XX号")
    private String buyerAddress;

    @Schema(description = "需方邮编", example = "200000")
    private String postCode;

    @Schema(description = "需方电话", example = "021-12345678")
    private String buyerPhone;

    @Schema(description = "需方传真", example = "021-87654321")
    private String fax;

    // ========== 金额信息 ==========

    @Schema(description = "原价（修改后会自动重新计算合同金额）", example = "10000.00")
    private BigDecimal originalAmount;

    @Schema(description = "折扣金额（修改后会自动重新计算合同金额）", example = "500.00")
    private BigDecimal discountedAmount;

    // ========== 日期信息 ==========

    @Schema(description = "签署日期（格式 yyyy-MM-dd）", example = "2026-12-01")
    private String contractDate;

    @Schema(description = "交货日期（格式 yyyy-MM-dd）", example = "2026-12-15")
    private String deliveryDate;

    // ========== 明细修改 ==========

    @Schema(description = "合同明细修改列表（只修改传入的明细）")
    private List<ContractItemUpdateRequest> items;

    // ========== 操作人信息 ==========

    @Schema(description = "操作人ID", example = "admin")
    private String operatorId;

    @Schema(description = "操作人姓名", example = "管理员")
    private String operatorName;
}
