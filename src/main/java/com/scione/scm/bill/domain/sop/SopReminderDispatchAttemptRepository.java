package com.scione.scm.bill.domain.sop;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 催办轮次执行记录：以「计划时点 + 节点组合」为一行，用来发现没跑成功的轮次并补发。
 * <p>
 * 与消息级的 reminder_push_record 分工：
 * 那边记录「这条消息发出去没有」，这边记录「这一轮有没有跑起来」。
 * 候选查询失败、同步数据不健康这类情况不会产生任何推送记录，只有这张表能留下痕迹。
 */
public interface SopReminderDispatchAttemptRepository {

    /**
     * 本轮开始：落一条 RUNNING。同一时点重复触发走唯一键 upsert，
     * 尝试次数加一，这样补发不会凭空产生新行，去重判断才能命中原来那一批。
     */
    void begin(String nodeCodes, LocalDateTime scheduledAt, LocalDateTime startedAt);

    /**
     * 本轮流程正常跑完，不代表每条消息都成功。
     *
     * @param unresolvedCount 结束时仍处于 UNKNOWN 的待办数；大于0时即使状态是 SUCCESS 也要继续补发
     */
    void succeed(String nodeCodes, LocalDateTime scheduledAt, int todos, int pushes,
                 int unresolvedCount, LocalDateTime nextRetryAt);

    /**
     * 本轮失败：记录原因并安排下次补发。
     *
     * @param giveUp true 表示已达重试上限，转人工处理，不再自动补发
     */
    void fail(String nodeCodes, LocalDateTime scheduledAt, String errorMessage,
              LocalDateTime nextRetryAt, boolean giveUp);

    /**
     * 到期需要补发的轮次：流程失败，或跑完但仍有未决消息，且当天、未超次数上限。
     * 返回的 scheduledAt 必须原样用于重跑，换成当前时间会让发送去重失效。
     */
    List<RetryAttempt> dueRetries(LocalDateTime now, int maxAttempts);

    /**
     * 该时点之后是否已有同一组节点的新轮次接手。
     * 装箱每5分钟一轮，失败行若等15分钟再补，必然和新轮次撞车；有新轮次就交给它，不重复打扰。
     */
    boolean superseded(String nodeCodes, LocalDateTime scheduledAt);

    /** 放弃补发：不做重试，仅把原因写进记录，便于事后核对这一轮为什么没补。 */
    void abandon(String nodeCodes, LocalDateTime scheduledAt, String reason);

    /** 补发需要的原始时点与节点组合。 */
    record RetryAttempt(long id, String nodeCodes, LocalDateTime scheduledAt, int attempts) { }
}
