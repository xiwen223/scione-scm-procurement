package com.scione.scm.bill.domain.sop;

import com.scione.scm.bill.domain.sop.enums.NodeCode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 免登录催办外链的返回结构。
 * 页面按 nodeCode 分组展示；ownerName为实际收件人配置姓名，不包含手机号等敏感信息。
 * snapshotAt为消息发送时刻：链接内容按该时刻冻结，之后待办关闭/催办次数增加都不影响展示；
 * 为null表示老token兜底链接，页面按当前时间实时展示。
 */
public record SopReminderLinkView(
        String ownerUserid,
        String ownerName,
        LocalDateTime snapshotAt,
        List<NodeGroup> nodes) {

    /** 一个节点（如 PO创建未完成）下的全部待办。 */
    public record NodeGroup(NodeCode nodeCode, String nodeName, List<TodoItem> todos) {
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

    /** 一条历史原因：reason原因内容、createdAt填写时间。 */
    public record ReasonItem(String reason, LocalDateTime createdAt) {
    }
}
