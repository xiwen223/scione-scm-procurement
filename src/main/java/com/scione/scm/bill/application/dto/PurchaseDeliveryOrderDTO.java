package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryOrder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** PP 展开后的 PO 粒度交付事实。 */
@Data
public class PurchaseDeliveryOrderDTO {

    private Long orderId;
    private String orderSn;
    private String planSn;
    private String skus;
    private String productNames;
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
    private List<String> inboundSns;
    private int inboundCount;
    private Long validInboundQty;
    private String completionInboundSn;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime completionAt;
    private Long deliveryHours;
    private String deliveryStatus;
    private String currentNode;
    private boolean allocationAvailable;
    private Long allocatedQty;

    public static PurchaseDeliveryOrderDTO from(PurchaseDeliveryOrder order) {
        PurchaseDeliveryOrderDTO dto = new PurchaseDeliveryOrderDTO();
        dto.setOrderId(order.orderId());
        dto.setOrderSn(order.orderSn());
        dto.setPlanSn(order.planSn());
        dto.setSkus(order.skus());
        dto.setProductNames(order.productNames());
        dto.setStatusText(order.statusText());
        dto.setSupplier(order.supplier());
        dto.setWarehouse(order.warehouse());
        dto.setBuyer(order.buyer());
        dto.setSettlementMethod(order.settlementMethod());
        dto.setSettlementType(order.settlementType());
        dto.setSourceType(order.sourceType());
        dto.setCreatedAt(order.createdAt());
        dto.setApprovalAt(order.approvalAt());
        dto.setSentAt(order.sentAt());
        dto.setPurchaseQty(order.purchaseQty());
        dto.setReceiptSns(order.receiptSns());
        dto.setReceiptCount(order.receiptCount());
        dto.setInboundSns(order.inboundSns());
        dto.setInboundCount(order.inboundCount());
        dto.setValidInboundQty(order.validInboundQty());
        dto.setCompletionInboundSn(order.completionInboundSn());
        dto.setCompletionAt(order.completionAt());
        dto.setDeliveryHours(order.deliveryHours());
        dto.setDeliveryStatus(order.deliveryStatus());
        dto.setCurrentNode(order.currentNode());
        dto.setAllocationAvailable(order.allocationAvailable());
        dto.setAllocatedQty(order.allocatedQty());
        return dto;
    }
}
