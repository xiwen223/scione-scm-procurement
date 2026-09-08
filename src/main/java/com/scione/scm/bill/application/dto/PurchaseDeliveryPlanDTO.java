package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryPlan;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** PP 粒度采购交付分析列表项。 */
@Data
public class PurchaseDeliveryPlanDTO {

    private String planSn;
    private String productName;
    private String sku;
    private String statusText;
    private String approvalStatus;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime approvalAt;
    private Long planQty;
    private Long purchaseQty;
    private int relatedOrderCount;
    private List<String> orderSns;
    private List<String> buyers;
    private List<String> suppliers;
    private List<String> warehouses;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastPoCreatedAt;
    private Long orderLeadHours;
    private String orderTimeliness;
    private boolean orderCoverageConfirmed;
    private String currentNode;
    private Long maxDeliveryHours;
    private String deliveryStatus;
    private boolean allocationAvailable;
    private Long inboundQty;
    private BigDecimal achievementRate;

    public static PurchaseDeliveryPlanDTO from(PurchaseDeliveryPlan plan) {
        PurchaseDeliveryPlanDTO dto = new PurchaseDeliveryPlanDTO();
        dto.setPlanSn(plan.planSn());
        dto.setProductName(plan.productName());
        dto.setSku(plan.sku());
        dto.setStatusText(plan.statusText());
        dto.setApprovalStatus(plan.approvalStatus());
        dto.setCreatedAt(plan.createdAt());
        dto.setApprovalAt(plan.approvalAt());
        dto.setPlanQty(plan.planQty());
        dto.setPurchaseQty(plan.purchaseQty());
        dto.setRelatedOrderCount(plan.relatedOrderCount());
        dto.setOrderSns(plan.orderSns());
        dto.setBuyers(plan.buyers());
        dto.setSuppliers(plan.suppliers());
        dto.setWarehouses(plan.warehouses());
        dto.setLastPoCreatedAt(plan.lastPoCreatedAt());
        dto.setOrderLeadHours(plan.orderLeadHours());
        dto.setOrderTimeliness(plan.orderTimeliness());
        dto.setOrderCoverageConfirmed(plan.orderCoverageConfirmed());
        dto.setCurrentNode(plan.currentNode());
        dto.setMaxDeliveryHours(plan.maxDeliveryHours());
        dto.setDeliveryStatus(plan.deliveryStatus());
        dto.setAllocationAvailable(plan.allocationAvailable());
        dto.setInboundQty(plan.inboundQty());
        dto.setAchievementRate(plan.achievementRate());
        return dto;
    }
}
