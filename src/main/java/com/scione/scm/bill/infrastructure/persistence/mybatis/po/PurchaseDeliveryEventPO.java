package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PurchaseDeliveryEventPO {
    private String eventId;
    private String sourceType;
    private String sourceSn;
    private LocalDateTime eventTime;
    private String title;
    private String description;
    private String operator;
    private Long quantity;
    private Long cumulativeQuantity;
}
