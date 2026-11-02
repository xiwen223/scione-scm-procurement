package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

/**
 * 跟单预警看板快照页签数量。
 */
@Data
public class DeliveryAlertSnapshotSummaryPO {

    private long totalCount;
    private long doneCount;
    private long normalCount;
    private long dueSoonCount;
    private long overdueCount;
    private long manualCount;
    private long kitCount;
}
