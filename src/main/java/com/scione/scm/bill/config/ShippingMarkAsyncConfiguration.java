package com.scione.scm.bill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 箱唛标签异步生成任务的独立线程池。
 */
@Configuration
@EnableAsync
public class ShippingMarkAsyncConfiguration {

    @Bean("shippingMarkTaskExecutor")
    public Executor shippingMarkTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("shipping-mark-");
        executor.initialize();
        return executor;
    }
}
