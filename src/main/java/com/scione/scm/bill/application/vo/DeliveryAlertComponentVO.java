package com.scione.scm.bill.application.vo;

import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertComponent;
import com.scione.scm.bill.domain.deliveryalert.DeliveryFollowUp;
import lombok.Data;
import java.util.List;

/**
 * 采购交付预警看板套装组件明细。
 */
@Data
public class DeliveryAlertComponentVO {

    private List<DeliveryFollowUp> followUps = List.of();

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
    private Integer exchangeQty;
    private Integer riskHours;
    private String riskLevel;
    private String riskLevelDesc;

    public static DeliveryAlertComponentVO from(DeliveryAlertComponent c) {
        DeliveryAlertComponentVO dto = new DeliveryAlertComponentVO();
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
        dto.setExchangeQty(c.exchangeQty());
        dto.setRiskHours(c.riskHours());
        dto.setRiskLevel(riskLevel(c.riskHours()));
        dto.setRiskLevelDesc(riskLevelDesc(c.riskHours()));
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
