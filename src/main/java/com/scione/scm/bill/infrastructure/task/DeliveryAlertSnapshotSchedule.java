package com.scione.scm.bill.infrastructure.task;

import com.scione.scm.bill.application.DeliveryAlertSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 跟单预警看板快照：每天 8 点半刷新；启动时快照早于今天 8 点半则后台补一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryAlertSnapshotSchedule {

    private final DeliveryAlertSnapshotService snapshotService;

    @Value("${delivery-alert.snapshot-enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${delivery-alert.snapshot-cron:0 30 8 * * ?}", zone = "Asia/Shanghai")
    public void refreshDaily() {
        if (!enabled) {
            log.info("跟单预警看板快照任务已关闭");
            return;
        }
        try {
            snapshotService.refresh();
        } catch (Exception exception) {
            log.error("跟单预警看板快照刷新失败", exception);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshIfStale() {
        if (!enabled || !snapshotService.isStale()) {
            return;
        }
        log.info("跟单预警看板快照已早于今日刷新时间（8 点半），启动后后台补刷");
        CompletableFuture.runAsync(() -> {
            try {
                snapshotService.refresh();
            } catch (Exception exception) {
                log.error("跟单预警看板快照初始化失败", exception);
            }
        });
    }
}
