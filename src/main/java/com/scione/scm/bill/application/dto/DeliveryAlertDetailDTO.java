package com.scione.scm.bill.application.dto;

import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertDetail;
import lombok.Data;

import java.util.List;

/**
 * 采购交付预警详情。
 */
@Data
public class DeliveryAlertDetailDTO {

    private String groupKey;
    private Long componentId;
    private String title;
    private boolean component;
    private List<DeliveryAlertEventDTO> events;

    public static DeliveryAlertDetailDTO from(DeliveryAlertDetail detail) {
        DeliveryAlertDetailDTO dto = new DeliveryAlertDetailDTO();
        dto.setGroupKey(detail.groupKey());
        dto.setComponentId(detail.componentId());
        dto.setTitle(detail.title());
        dto.setComponent(detail.component());
        dto.setEvents(detail.events().stream().map(DeliveryAlertEventDTO::from).toList());
        return dto;
    }
}
