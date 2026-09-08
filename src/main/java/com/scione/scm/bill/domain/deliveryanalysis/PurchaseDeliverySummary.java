package com.scione.scm.bill.domain.deliveryanalysis;

import java.time.LocalDateTime;

/** PP 当前节点分布汇总；超时不是独立节点。 */
public record PurchaseDeliverySummary(
        LocalDateTime asOf,
        String syncStatus,
        LocalDateTime dataUpdatedAt,
        long total,
        long ppCreateCount,
        long ppApprovalCount,
        long poCreateCount,
        long poApprovalCount,
        long supplierSentCount,
        long financePaymentCount,
        long receiptCount,
        long inboundCreateCount,
        long completedCount,
        long orderOverdueCount,
        long deliveryOverdueCount) {
}
