package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliverySummary;
import lombok.Data;

import java.time.LocalDateTime;

/** 九个 PRD 节点的 PP 数量汇总。 */
@Data
public class PurchaseDeliverySummaryDTO {

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime asOf;
    private String syncStatus;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
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

    public static PurchaseDeliverySummaryDTO from(PurchaseDeliverySummary summary) {
        PurchaseDeliverySummaryDTO dto = new PurchaseDeliverySummaryDTO();
        dto.setAsOf(summary.asOf());
        dto.setSyncStatus(summary.syncStatus());
        dto.setDataUpdatedAt(summary.dataUpdatedAt());
        dto.setTotal(summary.total());
        dto.setPpCreateCount(summary.ppCreateCount());
        dto.setPpApprovalCount(summary.ppApprovalCount());
        dto.setPoCreateCount(summary.poCreateCount());
        dto.setPoApprovalCount(summary.poApprovalCount());
        dto.setSupplierSentCount(summary.supplierSentCount());
        dto.setFinancePaymentCount(summary.financePaymentCount());
        dto.setReceiptCount(summary.receiptCount());
        dto.setInboundCreateCount(summary.inboundCreateCount());
        dto.setCompletedCount(summary.completedCount());
        dto.setOrderOverdueCount(summary.orderOverdueCount());
        dto.setDeliveryOverdueCount(summary.deliveryOverdueCount());
        return dto;
    }
}
