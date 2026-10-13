package com.scione.scm.bill.application.vo;

import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertDetail;
import lombok.Data;

import java.util.List;

/**
 * 采购交付预警详情。
 */
@Data
public class DeliveryAlertDetailVO {

    private String groupKey;
    private Long componentId;
    private String title;
    private boolean component;
    private List<DeliveryAlertEventVO> events;

    public static DeliveryAlertDetailVO from(DeliveryAlertDetail detail) {
        DeliveryAlertDetailVO dto = new DeliveryAlertDetailVO();
        dto.setGroupKey(detail.groupKey());
        dto.setComponentId(detail.componentId());
        dto.setTitle(detail.title());
        dto.setComponent(detail.component());
        dto.setEvents(detail.events().stream().map(DeliveryAlertEventVO::from).toList());
        return dto;
    }
}
