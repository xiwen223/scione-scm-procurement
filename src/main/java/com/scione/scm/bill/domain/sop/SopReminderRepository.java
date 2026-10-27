package com.scione.scm.bill.domain.sop;

import com.scione.scm.bill.domain.sop.SopReminderPreview.Candidate;
import com.scione.scm.bill.domain.sop.SopReminderLinkView.ReasonItem;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 催办待办与推送记录的仓储端口，由基础设施层用 MyBatis 实现。
 * 方法按发送流程分为：建待办、发送去重、记录结果、补偿重试、免登录外链。
 */
public interface SopReminderRepository {

    record Link(String assigneeUserid) { }

    record TodoRow(long id, String nodeCode, String businessType, String businessSn,
                   LocalDateTime enteredAt, int reminderCount, LocalDateTime firstRemindedAt) { }

    record PreparedGroup(String token, List<Long> todoIds, List<Integer> stages) { }

    record SkippedRecipient(String businessSn, String receiverKey, String errorCode, String errorMessage,
                            String itemKey, int stage) { }

    /** sendable=false时是否从页面隐藏，由节点规则决定，不在这里判断业务。 */
    /** unknownCount 为该待办历史上发送结果未知的次数，用于邮件标注第几次提醒。 */
    record DispatchDecision(boolean sendable, boolean hideFromPage, String reason, int unknownCount) { }

    record RetryBatch(long id, String contextJson) { }

    record RetryTodo(String businessSn, String itemKey, String roundKey, int stage, String nodeCode) { }

    /** 类型3为未发送异常，不关联推送明细，因此不会占用发送去重或成功次数。 */
    void recordSkippedRecipients(String nodeCode, LocalDateTime scheduledAt, List<SkippedRecipient> rows);

    /** 装箱与普通催办各自串行；锁在独立连接上，不随业务事务提交。 */
    <T> T withDispatchLock(boolean packing, Supplier<T> action);

    /** 调用方先取得发送锁。整组待办一次提交，再调用发送渠道。 */
    PreparedGroup prepareGroup(String nodeCode, String userid, List<Candidate> candidates);

    /** 普通节点按计划时点去重；节点9只按成功记录占用阶段和两次总额度。 */
    List<DispatchDecision> dispatchDecisions(List<Long> ids, List<Integer> stages,
                                             LocalDateTime scheduledAt, boolean goodsReady);

    /** 新单以该待办从未成功发送过为准；旧版本状态指纹和失败记录不当作成功。 */
    List<Long> newPackingTodoIds(List<Long> ids, LocalDateTime scheduledAt);

    /** 隐藏已经用完提醒阶段的节点9待办；保留历史，进入下一阶段时可由建待办重新激活。 */
    void suppressGoodsReadyTodos(String userid, List<Long> ids);

    /** 补偿也核对成功额度，禁止旧失败批次在后续已经成功两次后再发。调用方持普通发送锁。 */
    boolean retryWithinGoodsReadyQuota(long batchId);

    /** 节点5缺失真实起点时取消当前催办，避免旧创建时间兜底值继续显示。 */
    void cancelMissingRfStartTodos(List<String> businessSns);

    /** 只关闭发送前已明确退出该节点的本批待办；无法确认的记录保持原状。 */
    void closeVerifiedTodos(String nodeCode, String userid, List<Long> ids);

    /** 最新完整候选中已退出的待办标记结束，历史保留。数据有疑问的单号暂不关闭。 */
    void closeAbsentTodos(String nodeCode, List<Long> activeIds, List<String> uncertainSns);

    /** 发送前保存消息批次和完整清单；保存失败不调用渠道。成功计数以消息批次状态2为准。 */
    long startPush(String userid, List<Long> todoIds, List<Integer> stages,
                   LocalDateTime scheduledAt, String contextJson);

    /** 状态仅写在消息批次：2成功、3明确失败、4结果未知。未知结果不自动重发。 */
    void finishPush(long batchId, int sendStatus, String resultJson, LocalDateTime nextRetryAt);

    /** 调用方已持对应发送锁：同类正常发送尚未结束时无法进入核查。 */
    List<Long> staleSending(boolean packing, LocalDateTime before);

    boolean markSendingUnknown(long id, boolean packing, LocalDateTime before, String outcomeJson);

    /** 分区筛选后再LIMIT，避免另一类失败批次占满前20条而阻塞本类补偿。 */
    List<RetryBatch> dueRetries(LocalDateTime now, boolean packing);

    /** 更新成功才可以请求渠道。仍用原批次，次数在请求前持久化，进程退出也不会重置。 */
    boolean claimRetry(long id, String contextJson, LocalDateTime now);

    void stopRetry(long id);

    /** 重试必须同时核对单据、SKU、进入节点的轮次与提醒阶段，不能只看单号仍在候选中。 */
    List<RetryTodo> retryTodos(long id, String userid);

    boolean matchesRetry(Candidate candidate, RetryTodo todo, String userid);

    int retryTodoCount(long id);

    Link findLink(String token);

    /** 成功次数只统计关联到成功批次的明细，不把发送中/未知结果算成成功。 */
    List<TodoRow> loadTodos(String token);

    Map<Long, List<ReasonItem>> loadReasons(List<Long> todoIds);

    /** 用待办ID精确定位节点和轮次，同时校验token归属；不再按单号LIMIT 1猜记录。 */
    void addReason(String token, long todoId, String reason);

    /**
     * 清理已关闭待办的推送明细。待办关闭后不会再被催办，这段历史对去重和额度判断都已无用，
     * 只保留短期追溯窗口；活跃待办的明细一条都不动。
     *
     * @param closedBefore 待办最后更新时间早于该时间，即视为关闭已满保留期
     * @return 本批删除行数，等于 limit 时说明可能还有，调用方继续下一批
     */
    int purgeClosedTodoDetails(LocalDateTime closedBefore, int limit);

    /** 清理未发送异常记录；这类记录不参与去重、额度与自动重试，只用于排查为什么没发出去。 */
    int purgeSkippedRecords(LocalDateTime scheduledBefore, int limit);

    /** 统计待清理的已关闭待办明细行数，供预演模式使用。 */
    int countClosedTodoDetails(LocalDateTime closedBefore);

    /** 统计待清理的未发送异常记录行数，供预演模式使用。 */
    int countSkippedRecords(LocalDateTime scheduledBefore);
}
