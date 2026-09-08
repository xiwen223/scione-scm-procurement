package com.scione.scm.bill.domain.deliveryanalysis;

import java.time.LocalDateTime;
import java.util.List;

/** PO 粒度采购交付事实。 */
public record PurchaseDeliveryOrder(
        Long orderId,
        String orderSn,
        String planSn,
        String skus,
        String productNames,
        String statusText,
        String supplier,
        String warehouse,
        String buyer,
        Integer settlementMethod,
        String settlementType,
        String sourceType,
        LocalDateTime createdAt,
        LocalDateTime approvalAt,
        LocalDateTime sentAt,
        Long purchaseQty,
        List<String> receiptSns,
        int receiptCount,
        List<String> inboundSns,
        int inboundCount,
        Long validInboundQty,
        String completionInboundSn,
        LocalDateTime completionAt,
        Long deliveryHours,
        String deliveryStatus,
        String currentNode,
        boolean allocationAvailable,
        Long allocatedQty) {
}
