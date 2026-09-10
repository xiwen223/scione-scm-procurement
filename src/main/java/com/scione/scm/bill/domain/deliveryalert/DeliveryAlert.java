package com.scione.scm.bill.domain.deliveryalert;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购交付预警看板主记录（只读聚合视图）。
 *
 * <p>由「查询跟单预警看板页面主记录.sql」按 group_key 分组聚合而成，
 * 多值字段（采购单号、计划号、供应商、采购员、仓库、状态）在 SQL 中通过
 * GROUP_CONCAT 合并，映射到领域层时拆分为列表。</p>
 */
public record DeliveryAlert(
        String groupKey,
        List<String> types,
        String purchaseMode,
        String productName,
        String sku,
        long skuCount,
        List<String> orderSns,
        List<String> planSns,
        List<String> optNames,
        List<String> suppliers,
        List<String> warehouses,
        List<String> statusTexts,
        Integer qtyPlan,
        Integer qtyReady,
        Integer riskHours,
        LocalDateTime createdTime) {
}
