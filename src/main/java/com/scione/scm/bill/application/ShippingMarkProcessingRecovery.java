package com.scione.scm.bill.application;

import com.scione.scm.bill.application.port.ShippingMarkTaskDispatcher;
import com.scione.scm.bill.domain.shippingmark.ShippingMarkRepository;
import com.scione.scm.bill.domain.shippingmark.enums.MarkStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 恢复因进程中断而遗留在“处理中”的箱唛任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShippingMarkProcessingRecovery {

    private final ShippingMarkRepository repository;
    private final ShippingMarkTaskDispatcher taskDispatcher;

    public void resumeProcessingTasks() {
        repository.findAll().stream()
                .filter(mark -> mark.getStatus() == MarkStatus.PROCESSING)
                .forEach(mark -> {
                    log.info("Resuming shipping mark task: markId={}, billNo={}", mark.getId(), mark.getBillNo());
                    taskDispatcher.dispatch(mark.getId());
                });
    }
}
