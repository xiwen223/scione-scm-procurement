package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 催办轮次执行记录的 Mapper。
 * 表结构见 reminder_dispatch_attempt，唯一键 (scheduled_at, node_codes) 保证同一时点只有一行。
 */
public interface SopReminderDispatchAttemptMapper {

    /** 补发扫描得出的待重跑轮次。 */
    record AttemptRow(long id, String nodeCodes, LocalDateTime scheduledAt, int attempts) { }

    /** 开轮：首次插入 RUNNING，重复触发累加 attempts 并复位状态。 */
    int upsertRunning(@Param("nodeCodes") String nodeCodes,
                      @Param("scheduledAt") LocalDateTime scheduledAt,
                      @Param("startedAt") LocalDateTime startedAt);

    /** 正常跑完：记录待办与推送数；仍有未决消息时保留补发时间。 */
    int markSucceeded(@Param("nodeCodes") String nodeCodes,
                      @Param("scheduledAt") LocalDateTime scheduledAt,
                      @Param("todos") int todos,
                      @Param("pushes") int pushes,
                      @Param("unresolvedCount") int unresolvedCount,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt,
                      @Param("finishedAt") LocalDateTime finishedAt);

    /** 失败或放弃：记录原因与下次补发时间。 */
    int markFailed(@Param("nodeCodes") String nodeCodes,
                   @Param("scheduledAt") LocalDateTime scheduledAt,
                   @Param("status") String status,
                   @Param("errorMessage") String errorMessage,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 当天到期、未超次数的待补发轮次：失败待补的、仍有未决消息的，以及 RUNNING 悬挂的。
     *
     * @param todayStart  当天零点，由调用方按业务时区算出，避免依赖数据库时区
     * @param staleBefore 早于该时间开始的 RUNNING 视为进程中断留下的悬挂行
     */
    List<AttemptRow> selectDueRetries(@Param("now") LocalDateTime now,
                                      @Param("maxAttempts") int maxAttempts,
                                      @Param("staleBefore") LocalDateTime staleBefore,
                                      @Param("todayStart") LocalDateTime todayStart);

    /** 同一组节点在该时点之后是否已经跑过新一轮，用于避免补发与常规轮次重复。 */
    int countLaterAttempts(@Param("nodeCodes") String nodeCodes,
                           @Param("scheduledAt") LocalDateTime scheduledAt,
                           @Param("todayStart") LocalDateTime todayStart);

    /** 清理历史轮次；补发只扫描当天，超过保留期的记录不再影响任何判断。 */
    int deleteBefore(@Param("scheduledBefore") LocalDateTime scheduledBefore,
                     @Param("limit") int limit);

    int countBefore(@Param("scheduledBefore") LocalDateTime scheduledBefore);
}
