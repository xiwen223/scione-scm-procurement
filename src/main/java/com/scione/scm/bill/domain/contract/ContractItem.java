package com.scione.scm.bill.domain.contract;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 合同明细领域模型。对应 contract_item 表。
 */
@Data
public class ContractItem {
    private Long id;
    private Long contractId;
    private String contractNo;
    private String sku;
    private Long productId;
    private String productName;
    private String specification;
    private Integer quantity;
    private String unit;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private LocalDate deliveryDate;
    private String warehouseName;
    private Integer casesNum;           // 箱数
    private Integer quantityPerCase;    // 单箱数量
    private String picUrl;              // 产品图片URL
    private String remark;              // 备注
}