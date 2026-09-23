package com.scione.scm.bill.infrastructure.task;

import com.scione.scm.bill.application.ContractCompletionSyncService;
import com.scione.scm.bill.application.PoSyncAppService;
import com.scione.scm.bill.config.ContractAutoCreateScheduleProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 原生调度：先同步领星 PO，再扫描待建合同 PO。
 * 自动创建与完成状态同步直接执行，不使用命名锁；同 PO 重复插入由数据库唯一约束拦截。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NativeProcurementSchedule {

    private final PoSyncAppService poSyncAppService;
    private final ContractCompletionSyncService contractCompletionSyncService;
    private final ContractAutoCreateScheduleProperties properties;

    @Scheduled(cron = "${contract.auto-create.cron:0 0/5 * * * ?}")
    public void execute() {
        if (!properties.isEnabled()) {
            log.debug("原生采购合同自动创建任务已关闭");
            return;
        }
        try {
            // pullAndSync 内部只会将本次从领星同步成功的 PO 单号交给自动建合同，
            // 并由 SQL 再限定 po_status=1、未建合同、未删除。这里不能再次扫描整个本地 PO 表。
            log.info("原生采购合同自动创建任务开始");
            PoSyncAppService.SyncResult sync = poSyncAppService.pullAndSync();
            log.info("原生采购合同自动创建任务结束：PO同步 total={}, success={}, failed={}；"
                            + "合同自动创建已在本次 PO 同步流程内完成（仅处理本次同步的待下单 PO）",
                    sync.total(), sync.success(), sync.failed());
        } catch (Exception ex) {
            log.error("原生采购合同自动创建任务异常", ex);
        }
    }

    /**
     * 履行中合同完成同步独立运行，不受自动创建任务开关影响。
     * 每次仅扫描 contract.status=3 的合同，并按其 PO 单号实时查询领星。
     */
    @Scheduled(cron = "${contract.completion-sync.cron:0 0/5 * * * ?}")
    public void syncExecutingContractsCompletion() {
        try {
            log.info("合同完成状态同步任务开始");
            ContractCompletionSyncService.SyncResult result = contractCompletionSyncService.sync();
            log.info("合同完成状态同步任务结束：scanned={}, completed={}, failed={}",
                    result.scanned(), result.completed(), result.failed());
        } catch (Exception ex) {
            log.error("合同完成状态同步任务异常", ex);
        }
    }
}
