package com.scione.scm.bill.domain.deliveryalert;

/**
 * 采购交付预警看板汇总统计（按风险天数分档 + 人工判断/套装快捷视图）。
 */
public record DeliveryAlertSummary(long total, long done, long normal, long dueSoon, long overdue, long manual, long kit) {
}
