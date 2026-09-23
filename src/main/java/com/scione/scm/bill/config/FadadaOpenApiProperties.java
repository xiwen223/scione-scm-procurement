package com.scione.scm.bill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

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
    private String apiSubVersion = "5.1";
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(60);
    private Duration tokenTtl = Duration.ofSeconds(7200);
    private Duration tokenRefreshAhead = Duration.ofSeconds(60);
    private int maxAttempts = 3;
    private Duration retryInitialBackoff = Duration.ofSeconds(2);
}
