package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跟单预警看板每日快照行。
 */
@Data
public class DeliveryAlertSnapshotPO {

    private String groupKey;
    private String types;
    private String purchaseMode;
    private String productName;
    private String sku;
    private long skuCount;
    private String orders;
    private String plans;
    private String optNames;
    private String suppliers;
    private String warehouses;
    private String statusTexts;
    private String followers;
    private Integer qtyPlan;
    private Integer qtyReady;
    private Integer riskHours;
    private String riskLevel;
    private LocalDateTime createdTime;
    private LocalDateTime snapshotAt;
}
