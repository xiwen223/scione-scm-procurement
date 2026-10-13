package com.scione.scm.bill.application.vo;

import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryPlanDetail;
import lombok.Data;

import java.util.List;

@Data
public class PurchaseDeliveryPlanDetailVO {
    private PurchaseDeliveryPlanVO plan;
    private List<PurchaseDeliveryOrderVO> orders;
    private List<PurchaseDeliveryEventVO> timeline;

    public static PurchaseDeliveryPlanDetailVO from(PurchaseDeliveryPlanDetail detail) {
        PurchaseDeliveryPlanDetailVO dto = new PurchaseDeliveryPlanDetailVO();
        dto.setPlan(PurchaseDeliveryPlanVO.from(detail.plan()));
        dto.setOrders(detail.orders().stream().map(PurchaseDeliveryOrderVO::from).toList());
        dto.setTimeline(detail.timeline().stream().map(PurchaseDeliveryEventVO::from).toList());
        return dto;
    }
}
