package com.scione.scm.bill.application.dto;

import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertSummary;
import lombok.Data;

/**
 * 采购交付预警看板汇总统计。
 */
@Data
public class DeliveryAlertSummaryDTO {

    private long total;
    private long done;
    private long normal;
    private long dueSoon;
    private long overdue;
    private long manual;
    private long kit;

    public static DeliveryAlertSummaryDTO from(DeliveryAlertSummary summary) {
        DeliveryAlertSummaryDTO dto = new DeliveryAlertSummaryDTO();
        dto.setTotal(summary.total());
        dto.setDone(summary.done());
        dto.setNormal(summary.normal());
        dto.setDueSoon(summary.dueSoon());
        dto.setOverdue(summary.overdue());
        dto.setManual(summary.manual());
        dto.setKit(summary.kit());
        return dto;
    }
}
