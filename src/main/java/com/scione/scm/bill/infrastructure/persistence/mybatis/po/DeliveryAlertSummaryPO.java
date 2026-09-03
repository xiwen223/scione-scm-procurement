package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

/**
 * 采购交付预警看板汇总统计查询结果。
 */
@Data
public class DeliveryAlertSummaryPO {

    private long totalCount;
    private long doneCount;
    private long normalCount;
    private long dueSoonCount;
    private long overdueCount;
    private long manualCount;
    private long kitCount;
}
