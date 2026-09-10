package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采购交付预警看板数据最近同步时间。
 */
@Data
public class DeliveryAlertSyncTimeDTO {

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastSyncTime;

    public static DeliveryAlertSyncTimeDTO from(LocalDateTime lastSyncTime) {
        DeliveryAlertSyncTimeDTO dto = new DeliveryAlertSyncTimeDTO();
        dto.setLastSyncTime(lastSyncTime);
        return dto;
    }
}
