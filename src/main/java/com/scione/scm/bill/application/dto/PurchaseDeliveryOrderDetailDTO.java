package com.scione.scm.bill.application.dto;

import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryOrderDetail;
import lombok.Data;

import java.util.List;

@Data
public class PurchaseDeliveryOrderDetailDTO {
    private PurchaseDeliveryOrderDTO order;
    private List<PurchaseDeliveryInboundDTO> inbounds;
    private List<PurchaseDeliveryEventDTO> timeline;

    public static PurchaseDeliveryOrderDetailDTO from(PurchaseDeliveryOrderDetail detail) {
        PurchaseDeliveryOrderDetailDTO dto = new PurchaseDeliveryOrderDetailDTO();
        dto.setOrder(PurchaseDeliveryOrderDTO.from(detail.order()));
        dto.setInbounds(detail.inbounds().stream().map(PurchaseDeliveryInboundDTO::from).toList());
        dto.setTimeline(detail.timeline().stream().map(PurchaseDeliveryEventDTO::from).toList());
        return dto;
    }
}
