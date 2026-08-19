package com.scione.scm.bill.infrastructure.task;

import com.scione.scm.bill.application.ShippingMarkProcessingService;
import com.scione.scm.bill.application.port.ShippingMarkTaskDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 使用 Spring 异步执行器投递箱唛后台任务。
 */
@Component
@RequiredArgsConstructor
public class AsyncShippingMarkTaskDispatcher implements ShippingMarkTaskDispatcher {

    private final ShippingMarkProcessingService processingService;

    @Override
    @Async("shippingMarkTaskExecutor")
    public void dispatch(Long markId) {
        processingService.process(markId);
    }
}
