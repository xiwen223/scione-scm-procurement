package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PurchaseDeliveryOrderPO {
    private Long orderId;
    private String orderSn;
    private String planSn;
    private String skus;
    private String productNames;
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
    private String inboundSns;
    private int inboundCount;
    private Long validInboundQty;
    private String completionInboundSn;
    private LocalDateTime completionAt;
    private Long deliveryHours;
    private String deliveryStatus;
    private String currentNode;
    private Boolean allocationAvailable;
    private Long allocatedQty;
}
