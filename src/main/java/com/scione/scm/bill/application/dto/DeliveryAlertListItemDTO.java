package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlert;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购交付预警看板主记录列表项。
 */
@Data
public class DeliveryAlertListItemDTO {

    private String groupKey;
    private List<String> types;
    private String typeDesc;
    private String purchaseMode;
    private String productName;
    private String sku;
    private long skuCount;
    private List<String> orderSns;
    private List<String> planSns;
    private List<String> optNames;
    private List<String> suppliers;
    private List<String> warehouses;
    private List<String> statusTexts;
    private Integer qtyPlan;
    private Integer qtyReady;
    private Integer riskHours;
    private String riskLevel;
    private String riskLevelDesc;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdTime;

    public static DeliveryAlertListItemDTO from(DeliveryAlert alert) {
        DeliveryAlertListItemDTO dto = new DeliveryAlertListItemDTO();
        dto.setGroupKey(alert.groupKey());
        dto.setTypes(alert.types());
        dto.setTypeDesc(String.join("/", alert.types()));
        dto.setPurchaseMode(alert.purchaseMode());
        dto.setProductName(alert.productName());
        dto.setSku(alert.sku());
        dto.setSkuCount(alert.skuCount());
        dto.setOrderSns(alert.orderSns());
        dto.setPlanSns(alert.planSns());
        dto.setOptNames(alert.optNames());
        dto.setSuppliers(alert.suppliers());
        dto.setWarehouses(alert.warehouses());
        dto.setStatusTexts(alert.statusTexts());
        dto.setQtyPlan(alert.qtyPlan());
        dto.setQtyReady(alert.qtyReady());
        dto.setRiskHours(alert.riskHours());
        dto.setRiskLevel(riskLevel(alert.riskHours()));
        dto.setRiskLevelDesc(riskLevelDesc(alert.riskHours()));
        dto.setCreatedTime(alert.createdTime());
        return dto;
    }

    private static String riskLevel(Integer riskHours) {
        if (riskHours == null || riskHours == -1) {
            return "done";
        }
        if (riskHours < 240) {
            return "normal";
        }
        if (riskHours < 360) {
            return "due-soon";
        }
        return "overdue";
    }

    private static String riskLevelDesc(Integer riskHours) {
        return switch (riskLevel(riskHours)) {
            case "done" -> "已完成";
            case "normal" -> "正常";
            case "due-soon" -> "即将超期";
            default -> "超期";
        };
    }
}
