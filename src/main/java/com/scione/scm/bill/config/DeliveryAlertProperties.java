package com.scione.scm.bill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 跟单预警看板查询口径配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "delivery-alert")
public class DeliveryAlertProperties {

    /** 纳入看板的采购单最早创建日期（含当天）。 */
    private LocalDate startDate = LocalDate.of(2026, 8, 1);
}
