package com.scione.scm.bill.application.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采购交付预警看板数据最近同步时间。
 */
@Data
public class DeliveryAlertSyncTimeVO {

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastSyncTime;

    public static DeliveryAlertSyncTimeVO from(LocalDateTime lastSyncTime) {
        DeliveryAlertSyncTimeVO dto = new DeliveryAlertSyncTimeVO();
        dto.setLastSyncTime(lastSyncTime);
        return dto;
    }
}
