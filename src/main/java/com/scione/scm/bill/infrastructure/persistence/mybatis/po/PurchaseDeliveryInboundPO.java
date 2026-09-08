package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PurchaseDeliveryInboundPO {
    private Long inboundId;
    private String inboundSn;
    private LocalDateTime createdAt;
    private long inboundQty;
    private long cumulativeQty;
    private Boolean completionInbound;
}
