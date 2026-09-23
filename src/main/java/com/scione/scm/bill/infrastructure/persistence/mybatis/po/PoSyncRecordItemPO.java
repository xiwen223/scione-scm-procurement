package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * po_sync_record_item 表持久化对象。
 */
@Data
public class PoSyncRecordItemPO {

    private Long id;
    private String purchaseOrderNo;
    private Long lxItemId;
    private String planSn;
    private Long productId;
    private String productName;
    private String sku;
    private String fnsku;
    private String model;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private Integer quantityPlan;
    private Integer quantityReal;
    private Integer quantityReceive;
    private String taxRate;
    private String spu;
    private String spuName;
    private String warehouseName;
    private LocalDate expectArriveTime;
    private String remark;
    private String attributeJson;
    private LocalDateTime syncTime;
    private Integer casesNum;           // 箱数
    private Integer quantityPerCase;    // 单箱数量
    private String picUrl;              // 产品图片URL
}