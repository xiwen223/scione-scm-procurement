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

    /**
     * 是否存在需要把 PP 列表投影收窄到「命中订单」的订单级过滤条件。存在时 findPage 需要
     * 走 filtered_plan_projection 重新聚合 order_sns/buyers/suppliers/warehouses 等字段；
     * 不存在时直接复用 plan_facts 已聚合好的列，省去一次 GROUP_CONCAT DISTINCT。
     */
    public boolean hasOrderFilter() {
        return isNotBlank(keyword) || isNotBlank(buyer) || isNotBlank(supplier)
                || isNotBlank(warehouse) || isNotBlank(deliveryStatus);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
