package com.scione.scm.bill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** 启用异步调用，并配置法大大告警使用的独立线程池。 */
@Configuration
@EnableAsync
public class FadadaAlertConfiguration {

    /**
     * 提供 @Async("fadadaAlertExecutor") 使用的执行器。
     * <p>核心线程 2 个、最大线程 4 个、队列容量 100；线程和队列均满时，
     * CallerRunsPolicy 会由调用方线程同步执行。服务关闭时最多等待 10 秒。</p>
     * @return 由 Spring 管理初始化及关闭的线程池
     */
    @Bean("fadadaAlertExecutor")
    public ThreadPoolTaskExecutor fadadaAlertExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("fadada-alert-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
