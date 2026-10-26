package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.domain.sop.SopReminderRepository.RetryBatch;
import com.scione.scm.bill.domain.sop.SopReminderRepository.RetryTodo;
import com.scione.scm.bill.domain.sop.SopReminderRepository.TodoRow;
import com.scione.scm.bill.domain.sop.SopReminderRepository.ViewState;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ReminderPushBatchPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 催办待办、推送记录与免登录外链的 Mapper。
 * 结果直接映射到领域记录；只有发送历史汇总这种内部投影才在 Mapper 内定义行结构。
 */
public interface SopReminderMapper {

    /** 未发送异常记录（record_type=3）；同一异常重跑按 skip_key 幂等。 */
    record SkippedRow(LocalDateTime scheduledAt, String nodeCode, String businessSn, String receiverKey,
                      String errorCode, String errorMessage, String skipKey) { }

    /** 建待办：同一单号同节点同责任人只保留一条，重复即重新激活。 */
    record TodoInsert(String nodeCode, String businessType, String businessSn, String itemKey,
                      String roundKey, LocalDateTime enteredAt, String assigneeUserid, String viewToken) { }

    record TodoKey(long id, String businessType, String businessSn, String itemKey) { }

    record PushDetailInsert(long todoId, int reminderStage, LocalDateTime scheduledAt) { }

    record ReasonRow(long todoId, String reason, LocalDateTime createdAt) { }

    /** 发送历史汇总：成功次数、最高成功阶段、时点占用、结果未知与受控重试。 */
    record DispatchHistoryRow(long todoId, int successes, int highestStage,
                              boolean slotBlocked, boolean unresolved, boolean retryPending) { }

    int insertSkippedRows(@Param("rows") List<SkippedRow> rows);

    /** 同一责任人按普通催办/关联货件分别保持稳定链接，不按自然日重建历史。 */
    List<String> findStableTokens(@Param("userid") String userid, @Param("packing") boolean packing);

    int upsertTodos(@Param("rows") List<TodoInsert> rows);

    List<TodoKey> findTodoKeys(@Param("nodeCode") String nodeCode, @Param("roundKey") String roundKey);

    int refreshTodoTokens(@Param("token") String token, @Param("userid") String userid,
                          @Param("packing") boolean packing);

    List<DispatchHistoryRow> findDispatchHistory(@Param("ids") List<Long> ids,
                                                 @Param("scheduledAt") LocalDateTime scheduledAt,
                                                 @Param("now") String now);

    int suppressGoodsReadyTodos(@Param("userid") String userid, @Param("ids") List<Long> ids);

    int countGoodsReadyQuotaBlocked(@Param("batchId") long batchId);

    int cancelMissingRfStartTodos(@Param("sns") List<String> sns);

    int closeVerifiedTodos(@Param("nodeCode") String nodeCode, @Param("userid") String userid,
                           @Param("ids") List<Long> ids);

    int closeAbsentTodos(@Param("nodeCode") String nodeCode, @Param("activeIds") List<Long> activeIds,
                         @Param("uncertainSns") List<String> uncertainSns);

    int insertPushBatch(ReminderPushBatchPO batch);

    int insertPushDetails(@Param("batchId") long batchId, @Param("rows") List<PushDetailInsert> rows);

    int finishPush(@Param("batchId") long batchId, @Param("sendStatus") int sendStatus,
                   @Param("resultJson") String resultJson, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    List<Long> findStaleSending(@Param("before") LocalDateTime before, @Param("packing") boolean packing);

    int markSendingUnknown(@Param("id") long id, @Param("before") LocalDateTime before,
                           @Param("packing") boolean packing, @Param("outcomeJson") String outcomeJson);

    List<RetryBatch> findDueRetries(@Param("now") LocalDateTime now, @Param("packing") boolean packing);

    int claimRetry(@Param("id") long id, @Param("contextJson") String contextJson, @Param("now") LocalDateTime now);

    int stopRetry(@Param("id") long id);

    List<RetryTodo> findRetryTodos(@Param("id") long id, @Param("userid") String userid);

    int countRetryTodos(@Param("id") long id);

    String findAssigneeByToken(@Param("token") String token);

    int recordView(@Param("token") String token, @Param("ip") String ip, @Param("userAgent") String userAgent);

    ViewState findViewState(@Param("token") String token);

    List<TodoRow> findActiveTodos(@Param("token") String token);

    List<ReasonRow> findReasons(@Param("ids") List<Long> ids);

    int insertReason(@Param("token") String token, @Param("todoId") long todoId, @Param("reason") String reason);
}
