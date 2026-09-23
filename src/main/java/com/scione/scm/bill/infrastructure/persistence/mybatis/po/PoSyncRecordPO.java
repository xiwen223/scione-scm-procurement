package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * po_sync_record 表持久化对象。
 */
@Data
public class PoSyncRecordPO {

    private Long id;
    private String purchaseOrderNo;
    private String customOrderSn;
    private Long supplierId;
    private String supplierName;
    private String supplierPhone;
    private String contactPerson;
    private Integer poStatus;
    private String poStatusText;
    private Integer statusShipped;
    private String statusShippedText;
    private BigDecimal amountTotal;
    private BigDecimal totalPrice;
    private Integer quantityTotal;
    private String warehouseName;
    private String remark;
    private LocalDateTime poOrderTime;
    private LocalDateTime poCreateTime;
    private LocalDateTime poUpdateTime;
    private LocalDateTime syncTime;
}