package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采购交付预警看板主记录分组查询结果。
 *
 * <p>多值字段（orders/plans/optNames/suppliers/warehouses/statusText）在 SQL 中
 * 由 GROUP_CONCAT 合并为逗号分隔字符串，仓储层再拆分为列表。</p>
 */
@Data
public class DeliveryAlertPO {

    private String groupKey;
    private String type;
    private String purchaseMode;
    private String productName;
    private String sku;
    private long skuCount;
    private String orders;
    private String plans;
    private String optNames;
    private String suppliers;
    private String warehouses;
    private String statusText;
    private Integer qtyPlan;
    private Integer qtyReady;
    private Integer risk;
    private LocalDateTime createdTime;
}
