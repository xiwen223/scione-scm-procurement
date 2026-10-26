package com.scione.scm.bill.domain.sop;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 免登录催办外链的返回结构。
 * 页面按 nodeCode 分组展示；ownerName为实际收件人配置姓名，不包含手机号等敏感信息。
 */
public record SopReminderLinkView(
        String ownerUserid,
        String ownerName,
        LocalDateTime firstViewedAt,
        LocalDateTime lastViewedAt,
        int viewCount,
        List<NodeGroup> nodes) {

    /** 一个节点（如 PO创建未完成）下的全部待办。 */
    public record NodeGroup(String nodeCode, String nodeName, List<TodoItem> todos) {
    }

    /** 一条待办；todoId用于精确提交原因；reasons是该待办累计的历次原因，最新在前。 */
    public record TodoItem(
            long todoId,
            String businessSn,
            String businessType,
            LocalDateTime enteredAt,
            int reminderCount,
            LocalDateTime firstRemindedAt,
            List<ReasonItem> reasons) {
    }

    public record ReasonItem(String reason, LocalDateTime createdAt) {
    }
}
