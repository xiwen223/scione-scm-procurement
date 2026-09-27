package com.scione.scm.bill.infrastructure.task;

import com.scione.scm.bill.application.ContractAutoCreateService;
import com.scione.scm.bill.application.ContractCompletionSyncService;
import com.scione.scm.bill.application.PoSyncAppService;
import com.scione.scm.bill.config.ContractAutoCreateScheduleProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 原生调度：先同步领星 PO，再扫描待建合同 PO。
 * MySQL GET_LOCK 防止多实例或同实例任务重叠时重复执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NativeProcurementSchedule {

    private static final String LOCK_NAME = "scione-scm-procurement:contract-auto-create";

    private final PoSyncAppService poSyncAppService;
    private final ContractAutoCreateService contractAutoCreateService;
    private final ContractCompletionSyncService contractCompletionSyncService;
    private final ContractAutoCreateScheduleProperties properties;
    private final DataSource dataSource;

    @Scheduled(cron = "${contract.auto-create.cron:0 0/5 * * * ?}")
    public void execute() {
        if (!properties.isEnabled()) {
            log.debug("原生采购合同自动创建任务已关闭");
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection)) {
                log.info("原生采购合同自动创建任务跳过：其他实例正在执行");
                return;
            }
            try {
                log.info("原生采购合同自动创建任务开始");
                PoSyncAppService.SyncResult sync = poSyncAppService.pullAndSync();
                ContractAutoCreateService.AutoCreateResult create = contractAutoCreateService.autoCreate(null);
                ContractCompletionSyncService.SyncResult completion = contractCompletionSyncService.sync();
                log.info("原生采购合同自动创建任务结束：PO同步 total={}, success={}, failed={}; 合同创建 total={}, created={}, skipped={}, failed={}",
                        sync.total(), sync.success(), sync.failed(),
                        create.total(), create.created(), create.skipped(), create.failed());
                log.info("领星采购单完成状态同步结束：scanned={}, completed={}", completion.scanned(), completion.completed());
            } finally {
                releaseLock(connection);
            }
        } catch (Exception ex) {
            log.error("原生采购合同自动创建任务异常", ex);
        }
    }

    private boolean tryLock(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.execute();
        } catch (Exception ex) {
            log.error("原生采购合同自动创建任务释放数据库锁失败", ex);
        }
    }
}
