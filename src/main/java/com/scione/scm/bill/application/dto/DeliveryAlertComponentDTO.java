package com.scione.scm.bill.application.dto;

import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertComponent;
import lombok.Data;

/**
 * 采购交付预警看板套装组件明细。
 */
@Data
public class DeliveryAlertComponentDTO {

    private Long componentId;
    private String sku;
    private String productName;
    private String orderSn;
    private String planSn;
    private String purchaseMode;
    private String supplier;
    private String warehouse;
    private String optName;
    private String status;
    private Integer expected;
    private Integer arrived;
    private Integer qualified;
    private Integer returned;
    private Integer riskDays;
    private String riskLevel;
    private String riskLevelDesc;

    public static DeliveryAlertComponentDTO from(DeliveryAlertComponent c) {
        DeliveryAlertComponentDTO dto = new DeliveryAlertComponentDTO();
        dto.setComponentId(c.componentId());
        dto.setSku(c.sku());
        dto.setProductName(c.productName());
        dto.setOrderSn(c.orderSn());
        dto.setPlanSn(c.planSn());
        dto.setPurchaseMode(c.purchaseMode());
        dto.setSupplier(c.supplier());
        dto.setWarehouse(c.warehouse());
        dto.setOptName(c.optName());
        dto.setStatus(c.status());
        dto.setExpected(c.expected());
        dto.setArrived(c.arrived());
        dto.setQualified(c.qualified());
        dto.setReturned(c.returned());
        dto.setRiskDays(c.riskDays());
        dto.setRiskLevel(riskLevel(c.riskDays()));
        dto.setRiskLevelDesc(riskLevelDesc(c.riskDays()));
        return dto;
    }

    private static String riskLevel(Integer riskDays) {
        if (riskDays == null || riskDays == -1) {
            return "done";
        }
        if (riskDays <= 10) {
            return "normal";
        }
        if (riskDays <= 15) {
            return "due-soon";
        }
        return "overdue";
    }

    private static String riskLevelDesc(Integer riskDays) {
        return switch (riskLevel(riskDays)) {
            case "done" -> "已完成";
            case "normal" -> "正常";
            case "due-soon" -> "即将超期";
            default -> "超期";
        };
    }
}
