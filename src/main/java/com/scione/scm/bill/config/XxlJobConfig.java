package com.scione.scm.bill.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class XxlJobConfig {

    private final XxlJobProperties properties;

    @Bean
    public XxlJobSpringExecutor xxlJobSpringExecutor() {
        log.info("初始化 XXL-Job 执行器, appname={}, admin={}",
                properties.getExecutor().getAppname(), properties.getAdmin().getAddresses());
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(properties.getAdmin().getAddresses());
        executor.setAccessToken(properties.getAccessToken());
        executor.setAppname(properties.getExecutor().getAppname());
        executor.setIp(properties.getExecutor().getIp());
        executor.setPort(properties.getExecutor().getPort());
        executor.setLogPath(properties.getExecutor().getLogpath());
        executor.setLogRetentionDays(properties.getExecutor().getLogretentiondays());
        return executor;
    }
}