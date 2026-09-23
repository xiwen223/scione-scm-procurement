package com.scione.scm.bill.domain.posync;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 采购单同步记录（单头）领域模型。
 */
@Data
public class PoSyncRecord {

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
    private Integer hasContract;
    private Long contractId;
    private LocalDateTime syncTime;

    private List<PoSyncRecordItem> items = new ArrayList<>();
}