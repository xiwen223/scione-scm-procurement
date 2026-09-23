package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * contract_item 表持久化对象。
 */
@Data
public class ContractItemPO {
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
    private String remark;
    private LocalDateTime createTime;
    private Integer casesNum;
    private Integer quantityPerCase;
    private String picUrl;
}