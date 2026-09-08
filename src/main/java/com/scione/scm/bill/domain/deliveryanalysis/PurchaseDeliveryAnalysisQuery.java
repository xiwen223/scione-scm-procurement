package com.scione.scm.bill.domain.deliveryanalysis;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购交付流程分析查询条件。所有耗时计算共享同一个 asOf。
 */
public record PurchaseDeliveryAnalysisQuery(
        LocalDateTime asOf,
        LocalDate startDate,
        LocalDate endDate,
        String keyword,
        String node,
        String buyer,
        String supplier,
        String warehouse,
        String deliveryStatus,
        String risk,
        String sortBy,
        String sortOrder,
        int pageNum,
        int pageSize) {

    public PurchaseDeliveryAnalysisQuery withConfiguredStartDate(LocalDate configuredStartDate) {
        LocalDate effectiveStartDate = startDate == null || startDate.isBefore(configuredStartDate)
                ? configuredStartDate
                : startDate;
        return new PurchaseDeliveryAnalysisQuery(
                asOf, effectiveStartDate, endDate, keyword, node, buyer, supplier, warehouse,
                deliveryStatus, risk, sortBy, sortOrder, pageNum, pageSize);
    }

    public int offset() {
        return Math.multiplyExact(pageNum - 1, pageSize);
    }
}
