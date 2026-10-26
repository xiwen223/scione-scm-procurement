package com.scione.scm.bill.interfaces;

import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.SopReminderSendService;
import com.scione.scm.bill.application.SopReminderService;
import com.scione.scm.bill.domain.sop.SopReminderPreview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 催办手动入口：用于联调与排障，不影响定时任务。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/sop-reminders")
@Tag(name = "SOP催办", description = "手动预览与测试发送")
public class SopReminderController {
    private final SopReminderService service;
    private final SopReminderSendService sendService;

    /** 只看候选与排除原因，不写库、不发送。 */
    @GetMapping("/preview")
    @Operation(summary = "按节点预览候选与排除原因")
    public ApiResponse<SopReminderPreview> preview(@RequestParam(defaultValue = "PO_CREATE") String nodeCode) {
        return ApiResponse.success(service.preview(nodeCode));
    }

    /** 验收接口：默认强制发送，不受历史推送去重影响；force=false 可回到定时任务的语义。 */
    @PostMapping("/test-send-standard")
    @Operation(summary = "仅发送已开启的普通催办节点，按人汇总；默认强制发送")
    public ApiResponse<SopReminderSendService.SendResult> testSendStandard(
            @RequestParam(defaultValue = "true") boolean force) {
        return ApiResponse.success(sendService.sendStandardForTest(force));
    }

    /** 验收接口：默认强制发送当前完整清单，不受"仅新单触发"限制。 */
    @PostMapping("/test-send-packing")
    @Operation(summary = "装箱催办；默认强制发送当前完整清单")
    public ApiResponse<SopReminderSendService.SendResult> testSendPacking(
            @RequestParam(defaultValue = "true") boolean force) {
        return ApiResponse.success(sendService.sendPackingForTest(force));
    }

    /**
     * 手动触发一次发送，只是把触发时机从 cron 改成调接口，其余与定时任务完全一致：
     * 同样校验节点 enabled、同样建待办、同样发消息、同样关单。
     * 需要 Nacos 开启 sop.reminder.send.test-enabled。
     * businessSn 为空时发该节点全部候选，传了就只发这一张单。
     */
    @PostMapping("/test-send")
    @Operation(summary = "手动触发一次发送（联调测试用）")
    public ApiResponse<SopReminderSendService.SendResult> testSend(
            @RequestParam(defaultValue = "PO_CREATE") String nodeCode,
            @RequestParam(required = false) String businessSn) {
        return ApiResponse.success(sendService.sendForTest(nodeCode, businessSn));
    }
}
