package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * shipping_mark_detail 表持久化对象。
 */
@Data
public class ShippingMarkDetailPO {

    private Long id;
    private String billNo;
    private String purchaseOrderNo;
    private String skuCode;
    private String skuName;
    private String skuImage;
    private Integer status;
    private String errorReason;
    private String labelFile;
    private String createdBy;
    private String creator;
    private LocalDateTime createdAt;
    private String updatedBy;
    private String updator;
    private LocalDateTime updatedAt;
}
