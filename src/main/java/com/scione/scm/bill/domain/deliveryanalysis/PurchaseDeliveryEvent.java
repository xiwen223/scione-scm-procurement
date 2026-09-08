package com.scione.scm.bill.domain.deliveryanalysis;

import java.time.LocalDateTime;

/** PP/PO 抽屉时间线事件。 */
public record PurchaseDeliveryEvent(
        String eventId,
        String sourceType,
        String sourceSn,
        LocalDateTime eventTime,
        String title,
        String description,
        String operator,
        Long quantity,
        Long cumulativeQuantity) {
}
