package com.scione.scm.bill.domain.deliveryalert;

import java.time.LocalDate;

/**
 * 采购交付预警看板主记录查询条件。
 */
public record DeliveryAlertQuery(
        LocalDate startDate,
        String keyword,
        String type,
        String supplier,
        String buyer,
        String warehouse,
        String riskLevel,
        String view,
        int pageNum,
        int pageSize) {

    public DeliveryAlertQuery withStartDate(LocalDate configuredStartDate) {
        return new DeliveryAlertQuery(
                configuredStartDate, keyword, type, supplier, buyer, warehouse, riskLevel, view, pageNum, pageSize);
    }

    public int offset() {
        return (pageNum - 1) * pageSize;
    }
}
