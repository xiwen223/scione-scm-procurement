package com.scione.scm.bill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 采购交付流程分析配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "purchase-delivery-analysis")
public class PurchaseDeliveryAnalysisProperties {

    /** 纳入报表的采购计划最早创建日期（含当天）。 */
    private LocalDate startDate = LocalDate.of(2026, 1, 1);
}
