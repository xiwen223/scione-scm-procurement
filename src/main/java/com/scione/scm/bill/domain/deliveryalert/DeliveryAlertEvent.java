package com.scione.scm.bill.domain.deliveryalert;

import java.time.LocalDateTime;

/**
 * 采购单业务日志映射的采购事实事件。
 */
public record DeliveryAlertEvent(
        Long id,
        String orderSn,
        LocalDateTime eventTime,
        String title,
        String description,
        String operator) {
}
