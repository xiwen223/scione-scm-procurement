package com.scione.scm.bill.application.vo;

import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryOrderDetail;
import lombok.Data;

import java.util.List;

@Data
public class PurchaseDeliveryOrderDetailVO {
    private PurchaseDeliveryOrderVO order;
    private List<PurchaseDeliveryInboundVO> inbounds;
    private List<PurchaseDeliveryEventVO> timeline;

    public static PurchaseDeliveryOrderDetailVO from(PurchaseDeliveryOrderDetail detail) {
        PurchaseDeliveryOrderDetailVO dto = new PurchaseDeliveryOrderDetailVO();
        dto.setOrder(PurchaseDeliveryOrderVO.from(detail.order()));
        dto.setInbounds(detail.inbounds().stream().map(PurchaseDeliveryInboundVO::from).toList());
        dto.setTimeline(detail.timeline().stream().map(PurchaseDeliveryEventVO::from).toList());
        return dto;
    }
}
