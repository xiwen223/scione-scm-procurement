package com.scione.scm.bill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 原生采购单同步与合同自动创建调度配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "contract.auto-create")
public class ContractAutoCreateScheduleProperties {
    /** 默认开启，本地、测试和生产环境均执行采购单同步与合同自动创建。 */
    private boolean enabled = true;
    /** Spring cron，默认每 5 分钟执行一次。 */
    private String cron = "0 0/5 * * * ?";
}
