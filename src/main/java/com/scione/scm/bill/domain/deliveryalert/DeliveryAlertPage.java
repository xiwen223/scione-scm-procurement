package com.scione.scm.bill.domain.deliveryalert;

import java.util.List;

/**
 * 采购交付预警看板主记录分页结果。
 */
public record DeliveryAlertPage(long total, List<DeliveryAlert> records) {
}
