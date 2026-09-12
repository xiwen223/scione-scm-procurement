package com.scione.scm.bill.domain.deliveryanalysis;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** PP 粒度采购交付事实。 */
public record PurchaseDeliveryPlan(
        String planSn,
        String productName,
        String sku,
        String statusText,
        String approvalStatus,
        LocalDateTime createdAt,
        LocalDateTime approvalAt,
        Long planQty,
        Long purchaseQty,
        int relatedOrderCount,
        List<String> orderSns,
        List<String> buyers,
        List<String> suppliers,
        List<String> warehouses,
        LocalDateTime lastPoCreatedAt,
        Long orderLeadHours,
        String orderTimeliness,
        boolean orderCoverageConfirmed,
        String currentNode,
        Long maxDeliveryHours,
        String deliveryStatus,
        boolean allocationAvailable,
        Long inboundQty,
        BigDecimal achievementRate,
        Long returnedQty,
        Long exchangeQty) {
}
