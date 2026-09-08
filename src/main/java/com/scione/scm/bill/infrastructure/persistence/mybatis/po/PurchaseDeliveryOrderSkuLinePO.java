package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PurchaseDeliveryOrderSkuLinePO {
    private String rowKey;
    private Long orderId;
    private String orderSn;
    private String planSn;
    private String sku;
    private String productName;
    private String statusText;
    private String supplier;
    private String warehouse;
    private String buyer;
    private Integer settlementMethod;
    private String settlementType;
    private String sourceType;
    private LocalDateTime createdAt;
    private LocalDateTime approvalAt;
    private LocalDateTime sentAt;
    private Long purchaseQty;
    private String receiptSns;
    private int receiptCount;
    private Long receiptQty;
    private String inboundSns;
    private int inboundCount;
    private Long validInboundQty;
    private String poCompletionInboundSn;
    private LocalDateTime poCompletionAt;
    private Long poDeliveryHours;
    private String poDeliveryStatus;
    private String poCurrentNode;
    private Boolean allocationAvailable;
    private Long allocatedQty;
}
