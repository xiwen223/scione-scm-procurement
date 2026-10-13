package com.scione.scm.bill.application.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryInbound;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PurchaseDeliveryInboundVO {
    private Long inboundId;
    private String inboundSn;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    private long inboundQty;
    private long cumulativeQty;
    private boolean completionInbound;

    public static PurchaseDeliveryInboundVO from(PurchaseDeliveryInbound inbound) {
        PurchaseDeliveryInboundVO dto = new PurchaseDeliveryInboundVO();
        dto.setInboundId(inbound.inboundId());
        dto.setInboundSn(inbound.inboundSn());
        dto.setCreatedAt(inbound.createdAt());
        dto.setInboundQty(inbound.inboundQty());
        dto.setCumulativeQty(inbound.cumulativeQty());
        dto.setCompletionInbound(inbound.completionInbound());
        return dto;
    }
}
