package com.scione.scm.bill.interfaces;

import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.SopReminderLinkService;
import com.scione.scm.bill.domain.sop.SopReminderLinkView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 免登录催办外链入口：群消息里 @ 某人后附带的链接直接打开本接口。
 * 无登录态、无 Cookie 校验，访问控制依赖不可猜的 32 位 token。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/sop-reminders/link")
@Tag(name = "催办外链", description = "免登录查看催办待办")
public class SopReminderLinkController {

    private final SopReminderLinkService service;

    @GetMapping("/{token}")
    @Operation(summary = "按 token 打开催办详情")
    public ApiResponse<SopReminderLinkView> open(@PathVariable String token) {
        return ApiResponse.success(service.open(token));
    }

    /** 待办ID和token共同校验归属；同单号出现在多个节点时也不会写错。 */
    @PostMapping("/{token}/reason")
    @Operation(summary = "在免登录页面填写卡滞原因")
    public ApiResponse<Map<String, Object>> addReason(@PathVariable String token,
                                                      @RequestBody ReasonRequest body) {
        service.addReason(token, body == null ? null : body.todoId(), body == null ? null : body.reason());
        return ApiResponse.success(Map.of("saved", true));
    }

    /** 原因提交体；字段名与前端保持一致。 */
    public record ReasonRequest(Long todoId, String reason) {
    }
}
