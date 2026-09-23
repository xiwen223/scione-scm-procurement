package com.scione.scm.bill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * XXL-Job 执行器配置（对齐公司现有 xxl.job.admin / xxl.job.executor 结构）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "xxl.job")
public class XxlJobProperties {

    /** 通信 token，需与调度中心一致。 */
    private String accessToken = "";
    private Admin admin = new Admin();
    private Executor executor = new Executor();

    @Data
    public static class Admin {
        /** 调度中心地址，多个逗号分隔。 */
        private String addresses = "";
    }

    @Data
    public static class Executor {
        /** 执行器 AppName，需与调度中心执行器一致。 */
        private String appname = "scione-scm";
        /** 执行器 IP，为空自动获取。 */
        private String ip = "";
        /** 执行器端口，<=0 自动分配。 */
        private int port = -1;
        /** 执行日志目录。 */
        private String logpath = "./logs/xxl-job";
        /** 日志保留天数。 */
        private int logretentiondays = 30;
    }
}