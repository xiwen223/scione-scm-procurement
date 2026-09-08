package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryOrderSkuLine;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** PP 展开后的 PO+SKU 粒度交付事实。 */
@Data
public class PurchaseDeliveryOrderSkuLineDTO {

    private String rowKey;
    private Long orderId;
    private String orderSn;
    private String planSn;
    private String sku;
    private String productName;
    private String statusText;
    private String supplier;
    private String warehouse;
    private String buyer;
    private Integer settlementMethod;
    private String settlementType;
    private String sourceType;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime approvalAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime sentAt;
    private Long purchaseQty;
    private List<String> receiptSns;
    private int receiptCount;
    private Long receiptQty;
    private List<String> inboundSns;
    private int inboundCount;
    private Long validInboundQty;
    private String poCompletionInboundSn;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime poCompletionAt;
    private Long poDeliveryHours;
    private String poDeliveryStatus;
    private String poCurrentNode;
    private boolean allocationAvailable;
    private Long allocatedQty;

    public static PurchaseDeliveryOrderSkuLineDTO from(PurchaseDeliveryOrderSkuLine line) {
        PurchaseDeliveryOrderSkuLineDTO dto = new PurchaseDeliveryOrderSkuLineDTO();
        dto.setRowKey(line.rowKey());
        dto.setOrderId(line.orderId());
        dto.setOrderSn(line.orderSn());
        dto.setPlanSn(line.planSn());
        dto.setSku(line.sku());
        dto.setProductName(line.productName());
        dto.setStatusText(line.statusText());
        dto.setSupplier(line.supplier());
        dto.setWarehouse(line.warehouse());
        dto.setBuyer(line.buyer());
        dto.setSettlementMethod(line.settlementMethod());
        dto.setSettlementType(line.settlementType());
        dto.setSourceType(line.sourceType());
        dto.setCreatedAt(line.createdAt());
        dto.setApprovalAt(line.approvalAt());
        dto.setSentAt(line.sentAt());
        dto.setPurchaseQty(line.purchaseQty());
        dto.setReceiptSns(line.receiptSns());
        dto.setReceiptCount(line.receiptCount());
        dto.setReceiptQty(line.receiptQty());
        dto.setInboundSns(line.inboundSns());
        dto.setInboundCount(line.inboundCount());
        dto.setValidInboundQty(line.validInboundQty());
        dto.setPoCompletionInboundSn(line.poCompletionInboundSn());
        dto.setPoCompletionAt(line.poCompletionAt());
        dto.setPoDeliveryHours(line.poDeliveryHours());
        dto.setPoDeliveryStatus(line.poDeliveryStatus());
        dto.setPoCurrentNode(line.poCurrentNode());
        dto.setAllocationAvailable(line.allocationAvailable());
        dto.setAllocatedQty(line.allocatedQty());
        return dto;
    }
}
