package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryEvent;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PurchaseDeliveryEventDTO {
    private String eventId;
    private String sourceType;
    private String sourceSn;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime eventTime;
    private String title;
    private String description;
    private String operator;
    private Long quantity;
    private Long cumulativeQuantity;

    public static PurchaseDeliveryEventDTO from(PurchaseDeliveryEvent event) {
        PurchaseDeliveryEventDTO dto = new PurchaseDeliveryEventDTO();
        dto.setEventId(event.eventId());
        dto.setSourceType(event.sourceType());
        dto.setSourceSn(event.sourceSn());
        dto.setEventTime(event.eventTime());
        dto.setTitle(event.title());
        dto.setDescription(event.description());
        dto.setOperator(event.operator());
        dto.setQuantity(event.quantity());
        dto.setCumulativeQuantity(event.cumulativeQuantity());
        return dto;
    }
}
