package com.scione.scm.bill.application.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertEvent;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采购事实事件。
 */
@Data
public class DeliveryAlertEventVO {

    private Long id;
    private String orderSn;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime eventTime;
    private String title;
    private String description;
    private String operator;

    public static DeliveryAlertEventVO from(DeliveryAlertEvent event) {
        DeliveryAlertEventVO dto = new DeliveryAlertEventVO();
        dto.setId(event.id());
        dto.setOrderSn(event.orderSn());
        dto.setEventTime(event.eventTime());
        dto.setTitle(event.title());
        dto.setDescription(event.description());
        dto.setOperator(event.operator());
        return dto;
    }
}
