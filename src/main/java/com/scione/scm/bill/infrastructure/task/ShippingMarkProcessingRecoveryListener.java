package com.scione.scm.bill.infrastructure.task;

import com.scione.scm.bill.application.ShippingMarkProcessingRecovery;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Spring 启动完成后触发箱唛处理中任务的恢复用例。
 */
@Component
@RequiredArgsConstructor
public class ShippingMarkProcessingRecoveryListener {

    private final ShippingMarkProcessingRecovery recovery;

    @EventListener(ApplicationReadyEvent.class)
    public void resumeProcessingTasks() {
        recovery.resumeProcessingTasks();
    }
}
