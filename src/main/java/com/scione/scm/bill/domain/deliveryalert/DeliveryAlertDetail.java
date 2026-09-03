package com.scione.scm.bill.domain.deliveryalert;

import java.util.List;

/**
 * 采购交付预警详情（只读）。
 */
public record DeliveryAlertDetail(
        String groupKey,
        Long componentId,
        String title,
        boolean component,
        List<DeliveryAlertEvent> events) {
}
