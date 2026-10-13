package com.scione.scm.bill.application.vo;

import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertSummary;
import lombok.Data;

/**
 * 采购交付预警看板汇总统计。
 */
@Data
public class DeliveryAlertSummaryVO {

    private long total;
    private long done;
    private long normal;
    private long dueSoon;
    private long overdue;
    private long manual;
    private long kit;

    public static DeliveryAlertSummaryVO from(DeliveryAlertSummary summary) {
        DeliveryAlertSummaryVO dto = new DeliveryAlertSummaryVO();
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
