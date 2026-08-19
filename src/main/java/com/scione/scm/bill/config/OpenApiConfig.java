package com.scione.scm.bill.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档基础配置。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI billOpenApi() {
        return new OpenAPI().info(new Info()
                .title("供应链单据服务 API")
                .version("v1")
                .description("供应链单据服务接口文档"));
    }
}
