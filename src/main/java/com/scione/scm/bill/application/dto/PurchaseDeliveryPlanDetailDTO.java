package com.scione.scm.bill.application.dto;

import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryPlanDetail;
import lombok.Data;

import java.util.List;

@Data
public class PurchaseDeliveryPlanDetailDTO {
    private PurchaseDeliveryPlanDTO plan;
    private List<PurchaseDeliveryOrderDTO> orders;
    private List<PurchaseDeliveryEventDTO> timeline;

    public static PurchaseDeliveryPlanDetailDTO from(PurchaseDeliveryPlanDetail detail) {
        PurchaseDeliveryPlanDetailDTO dto = new PurchaseDeliveryPlanDetailDTO();
        dto.setPlan(PurchaseDeliveryPlanDTO.from(detail.plan()));
        dto.setOrders(detail.orders().stream().map(PurchaseDeliveryOrderDTO::from).toList());
        dto.setTimeline(detail.timeline().stream().map(PurchaseDeliveryEventDTO::from).toList());
        return dto;
    }
}
