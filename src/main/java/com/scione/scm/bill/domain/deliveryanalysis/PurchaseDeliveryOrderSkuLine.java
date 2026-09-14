package com.scione.scm.bill.domain.deliveryanalysis;

import java.time.LocalDateTime;
import java.util.List;

/** 当前 PP 下的 PO+SKU 粒度采购交付事实；PO 级完成与 SLA 字段会在各 SKU 行重复，SKU 级字段按行各自计算。 */
public record PurchaseDeliveryOrderSkuLine(
        String rowKey,
        Long orderId,
        String orderSn,
        String planSn,
        String sku,
        String productName,
        String statusText,
        String subStatusText,
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
        Long receiptQty,
        List<String> inboundSns,
        int inboundCount,
        Long validInboundQty,
        String poCompletionInboundSn,
        LocalDateTime poCompletionAt,
        String skuCompletionInboundSn,
        LocalDateTime skuCompletionAt,
        Long poDeliveryHours,
        String poDeliveryStatus,
        Long skuDeliveryHours,
        String skuDeliveryStatus,
        String poCurrentNode,
        boolean allocationAvailable,
        Long allocatedQty) {
}
