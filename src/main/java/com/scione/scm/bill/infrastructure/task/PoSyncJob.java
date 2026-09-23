package com.scione.scm.bill.infrastructure.task;

import com.scione.scm.bill.application.PoSyncAppService;
import com.scione.scm.bill.application.PoSyncAppService.SyncResult;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 采购单同步定时任务。调度中心 JobHandler 名称：poSyncJob。
 */
@Component
@RequiredArgsConstructor
public class PoSyncJob {

    private final PoSyncAppService poSyncAppService;

    @XxlJob("poSyncJob")
    public void execute() {
        XxlJobHelper.log("采购单同步 Job 开始");
        SyncResult result = poSyncAppService.pullAndSync();
        XxlJobHelper.log("采购单同步 Job 结束：拉取 {}，成功 {}，失败 {}",
                result.total(), result.success(), result.failed());
        // pullAndSync 内部已吞掉异常，若有单条失败，标红让调度中心可见
        if (result.failed() > 0) {
            XxlJobHelper.handleFail("存在落库失败的采购单，失败数=" + result.failed());
        }
    }
}