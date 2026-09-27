package com.scione.scm.bill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 原生采购单同步与合同自动创建调度配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "contract.auto-create")
public class ContractAutoCreateScheduleProperties {
    /** 默认关闭，避免本地启动即创建合同；测试/生产在 Nacos 或环境变量显式开启。 */
    private boolean enabled = false;
    /** Spring cron，默认每 10 分钟执行一次。 */
    private String cron = "0 0/5 * * * ?";
}
