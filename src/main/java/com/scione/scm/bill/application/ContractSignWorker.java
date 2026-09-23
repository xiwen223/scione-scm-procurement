package com.scione.scm.bill.application;

import com.scione.scm.bill.domain.contract.*;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ContractMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.*;

/** 数据库队列不随页面关闭丢失；条件更新保证多个实例不重复领取任务。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContractSignWorker {
    private final ContractMapper mapper;
    private final ContractRepository repository;
    private final ContractSignAppService service;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Semaphore slots = new Semaphore(2);

    @Scheduled(fixedDelay = 3000)
    public void dispatch() {
        for (Long id : mapper.queuedSigns()) {
            if (!submit(id, "发起签署", () -> startSign(id))) break;
        }
    }

    private void startSign(Long id) {
        // 条件更新只允许 QUEUED → RUNNING；未抢到任务的线程退出，不再次调用法大大。
        if (mapper.claimSign(id) != 1) return;
        String operator = mapper.signOperator(id);
        try {
            service.startSign(id, operator);
        } catch (Exception ex) {
            // 建任务结果未知的任务保持 UNKNOWN；只将仍为 RUNNING 的前置失败恢复可编辑。
            String reason = ex.getMessage() == null ? "签署发起失败" : ex.getMessage();
            mapper.failSign(id, "FAILED", reason.substring(0, Math.min(1000, reason.length())));
            log.error("后台发起签署失败：contractId={}", id, ex);
            repository.findById(id).ifPresent(contract -> repository.saveOperationLog(
                    ContractOperationLog.ofUpdate(id, contract.getContractNo(), operator, operator,
                            "后台签署发起失败", reason)));
        }
    }

    /** 两类后台任务共用名额和异常处理，任何退出路径都归还名额。 */
    private boolean submit(Long id, String scene, Runnable action) {
        // 共享名额耗尽就留给下轮调度，避免线程池队列无限堆积；这不是合同创建的命名锁。
        if (!slots.tryAcquire()) return false;
        try {
            executor.execute(() -> {
                try { action.run(); }
                catch (Exception ex) { log.error("签署后台任务异常：scene={}, contractId={}", scene, id, ex); }
                finally { slots.release(); }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            slots.release();
            return false;
        }
    }

    @jakarta.annotation.PreDestroy
    public void stop() { executor.shutdown(); }

    /** 回调未送达时查询确认我方签署结果，不重新创建任务。 */
    @Scheduled(fixedDelay = 20000, initialDelay = 15000)
    public void reconcileCallbacks() {
        for (Long id : mapper.waitingSignCallbacks()) {
            if (!submit(id, "回调兜底", () -> {
                try {
                    mapper.touchWaitingSign(id);
                    service.syncFinishedSignTask(id, Contract.SYSTEM_OPERATOR);
                } catch (Exception ex) {
                    log.info("我方签署状态兜底查询失败，稍后重试：contractId={}, reason={}", id, ex.getMessage());
                }
            })) break;
        }
    }
}
