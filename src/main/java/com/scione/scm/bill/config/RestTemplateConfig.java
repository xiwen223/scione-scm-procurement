package com.scione.scm.bill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置类。
 * 用于从S3预签名URL下载文件。
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);  // 连接超时：10秒
        factory.setReadTimeout(60000);     // 读取超时：60秒（下载文件可能需要较长时间）
        return new RestTemplate(factory);
    }
}
