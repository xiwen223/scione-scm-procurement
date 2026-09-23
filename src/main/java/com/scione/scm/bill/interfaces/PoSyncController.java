package com.scione.scm.bill.interfaces;

import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.PoSyncAppService;
import com.scione.scm.bill.application.PoSyncAppService.SyncResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 采购单同步手动触发接口（联调用；生产由 XXL-Job 定时触发同一逻辑）。
 */
@RestController
@Validated
@RequestMapping("/api/v1/po-sync")
@RequiredArgsConstructor
@Tag(name = "采购单同步", description = "手动触发领星采购单拉取落库")
public class PoSyncController {

    private final PoSyncAppService poSyncAppService;

    @PostMapping("/trigger")
    @Operation(summary = "手动触发采购单同步",
            description = "不传时间则拉取最近窗口；传 start+end 则按自定义区间回补")
    public ApiResponse<SyncResult> trigger(
            @RequestParam(value = "start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(value = "end", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        SyncResult result = (start != null && end != null)
                ? poSyncAppService.pullAndSync(start, end)
                : poSyncAppService.pullAndSync();
        return ApiResponse.success(result);
    }
}