package com.scione.scm.bill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/**
 * 领星 OpenAPI 连接配置。应用凭据必须由环境变量或配置中心提供。
 */
@Data
@Component
@ConfigurationProperties(prefix = "lingxing.open-api")
public class LingxingOpenApiProperties {

    private URI endpoint = URI.create("https://openapi.lingxing.com");
    private String appId;
    private String appSecret;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);
    private Duration tokenTtl = Duration.ofMinutes(90);
    /** 同 appId + 路径的进程内排队最长等待时间，不是 HTTP 超时。 */
    private Duration requestQueueTimeout = Duration.ofSeconds(40);
    /** 同一接口两次请求开始之间的最小间隔；空闲足够久时不额外等待。 */
    private Duration minRequestInterval = Duration.ofMillis(1000);
}
