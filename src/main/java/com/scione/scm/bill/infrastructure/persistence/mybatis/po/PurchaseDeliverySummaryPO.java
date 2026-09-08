package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PurchaseDeliverySummaryPO {
    private String syncStatus;
    private LocalDateTime dataUpdatedAt;
    private long total;
    private long ppCreateCount;
    private long ppApprovalCount;
    private long poCreateCount;
    private long poApprovalCount;
    private long supplierSentCount;
    private long financePaymentCount;
    private long receiptCount;
    private long inboundCreateCount;
    private long completedCount;
    private long orderOverdueCount;
    private long deliveryOverdueCount;
}
