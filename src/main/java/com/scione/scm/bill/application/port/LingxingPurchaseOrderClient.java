package com.scione.scm.bill.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 领星采购单列表实时查询端口。
 *
 * <p>拉取阶段“全量落库、不做业务校验”，因此所有字段均可空，解析失败一律降级为 null，不抛业务异常。
 */
public interface LingxingPurchaseOrderClient {

    /**
     * 查询采购单列表（自动翻页汇总）。
     *
     * @param startTime       开始日期（双闭区间）
     * @param endTime         结束日期（双闭区间）
     * @param searchFieldTime 时间搜索维度：create_time / update_time / expect_arrive_time
     * @return 采购单单头（含明细）列表；无数据返回空列表
     */
    List<PurchaseOrderData> fetchPurchaseOrders(LocalDateTime startTime, LocalDateTime endTime, String searchFieldTime);

    /** 采购单单头（对应领星 data[]）。 */
    record PurchaseOrderData(
            String orderSn,
            String customOrderSn,
            Long supplierId,
            String supplierName,
            String contactPerson,
            String contactNumber,
            Integer status,
            String statusText,
            Integer statusShipped,
            String statusShippedText,
            BigDecimal amountTotal,
            BigDecimal totalPrice,
            Integer quantityTotal,
            String warehouseName,
            String remark,
            LocalDateTime orderTime,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            List<PurchaseOrderItemData> items) {
    }

    /** 采购单明细（对应领星 data[].item_list[]）。 */
    record PurchaseOrderItemData(
            Long lxItemId,
            String planSn,
            Long productId,
            String productName,
            String sku,
            String fnsku,
            String model,
            BigDecimal price,
            BigDecimal amount,
            Integer quantityPlan,
            Integer quantityReal,
            Integer quantityReceive,
            String taxRate,
            String spu,
            String spuName,
            String warehouseName,
            LocalDate expectArriveTime,
            String remark,
            String attributeJson) {
    }
}
