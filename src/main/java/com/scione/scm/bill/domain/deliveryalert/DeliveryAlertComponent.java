package com.scione.scm.bill.domain.deliveryalert;

/**
 * 采购交付预警看板套装组件明细（只读）。
 */
public record DeliveryAlertComponent(
        Long componentId,
        String sku,
        String productName,
        String orderSn,
        String planSn,
        String purchaseMode,
        String supplier,
        String warehouse,
        String optName,
        String status,
        Integer expected,
        Integer arrived,
        Integer qualified,
        Integer returned,
        Integer riskHours) {
}
