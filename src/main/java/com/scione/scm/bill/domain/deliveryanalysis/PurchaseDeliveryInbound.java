package com.scione.scm.bill.domain.deliveryanalysis;

import java.time.LocalDateTime;

/** 一张去重后的有效 IB 及其累计事实。 */
public record PurchaseDeliveryInbound(
        Long inboundId,
        String inboundSn,
        LocalDateTime createdAt,
        long inboundQty,
        long cumulativeQty,
        boolean completionInbound) {
}
