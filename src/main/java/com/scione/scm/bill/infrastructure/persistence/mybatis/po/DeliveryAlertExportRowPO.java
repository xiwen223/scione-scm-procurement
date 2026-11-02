package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 导出扁平行查询结果。
 */
@Data
public class DeliveryAlertExportRowPO {

    /** 采购明细 ID：同一明细可能落在多个主事项下，去重按这一列而不是 orderSn + sku。 */
    private Long componentId;
    private String groupKey;
    private String planSn;
    private String orderSn;
    private String sku;
    private String productName;
    private String supplier;
    private String buyer;
    private String warehouse;
    private Long qtyReady;
    private Long qtyPlan;
    private LocalDateTime createdTime;
    private String followerName;
    private LocalDate expectedDeliveryDate;
    private String reason;
    private String remark;
}
