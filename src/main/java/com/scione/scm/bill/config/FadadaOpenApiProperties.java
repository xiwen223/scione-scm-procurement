package com.scione.scm.bill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 法大大 V5 OpenAPI 连接配置。应用凭据必须由环境变量或配置中心提供。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fadada.open-api")
public class FadadaOpenApiProperties {

    /** 配置值必须包含 API 版本路径，例如 https://uat-api.fadada.com/api/v5。 */
    private URI endpoint = URI.create("https://uat-api.fadada.com/api/v5");
    private String appId;
    private String appSecret;
    private String openCorpId;
    /** 法大大签署完成回调地址；域名未就绪时可留空。 */
    private String notifyUrl;
    private String apiSubVersion = "5.1";
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(60);
    private Duration tokenTtl = Duration.ofSeconds(7200);
    private Duration tokenRefreshAhead = Duration.ofSeconds(60);
    private int maxAttempts = 3;
    private Duration retryInitialBackoff = Duration.ofSeconds(2);

    /**
     * 备用应用凭据。
     *
     * <p>法大大的企业（openCorpId）与签署任务都挂在某个应用（appId）之下：用应用 A 的凭据
     * 去操作应用 B 签出的任务，会报 210032 / 211150 / 211503。历史数据里存在同一批合同
     * 在不同时期用了不同应用签署的情况，这里登记全部应用凭据；调用失败且错误码属于
     * 「应用不匹配」时，客户端会用这些备用凭据自动重试。</p>
     */
    private List<AppCredential> apps = new ArrayList<>();

    /** 单套法大大应用凭据。 */
    @Data
    public static class AppCredential {
        /** 法大大应用 appId。 */
        private String appId;
        /** 法大大应用 appSecret。 */
        private String appSecret;
        /** 该应用下发起签署的企业 openCorpId，仅用于运维对照，不参与选路。 */
        private String openCorpId;
        /** 可选的 API 子版本覆盖，缺省继承主配置。 */
        private String apiSubVersion;
    }
}
