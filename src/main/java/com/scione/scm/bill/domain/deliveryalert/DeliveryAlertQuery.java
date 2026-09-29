package com.scione.scm.bill.domain.deliveryalert;

import java.time.LocalDate;
import java.util.List;

/**
 * 采购交付预警看板主记录查询条件。
 */
public record DeliveryAlertQuery(
        LocalDate startDate,
        String keyword,
        List<String> types,
        List<String> suppliers,
        List<String> buyers,
        List<String> warehouses,
        List<String> riskLevels,
        String view,
        LocalDate createDateFrom,
        LocalDate createDateTo,
        int pageNum,
        int pageSize) {

    public DeliveryAlertQuery withStartDate(LocalDate configuredStartDate) {
        return new DeliveryAlertQuery(
                configuredStartDate, keyword, types, suppliers, buyers, warehouses, riskLevels, view,
                createDateFrom, createDateTo, pageNum, pageSize);
    }

    public int offset() {
        return (pageNum - 1) * pageSize;
    }
}
