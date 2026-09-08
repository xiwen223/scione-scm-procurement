package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PurchaseDeliveryPlanPO {
    private String planSn;
    private String productName;
    private String sku;
    private String statusText;
    private String approvalStatus;
    private LocalDateTime createdAt;
    private LocalDateTime approvalAt;
    private Long planQty;
    private Long purchaseQty;
    private int relatedOrderCount;
    private String orderSns;
    private String buyers;
    private String suppliers;
    private String warehouses;
    private LocalDateTime lastPoCreatedAt;
    private Long orderLeadHours;
    private String orderTimeliness;
    private Boolean orderCoverageConfirmed;
    private String currentNode;
    private Long maxDeliveryHours;
    private String deliveryStatus;
    private Boolean allocationAvailable;
    private Long inboundQty;
    private BigDecimal achievementRate;
}
