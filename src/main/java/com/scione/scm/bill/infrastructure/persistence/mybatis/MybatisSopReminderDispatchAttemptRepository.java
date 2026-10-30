package com.scione.scm.bill.infrastructure.persistence.mybatis;

import com.scione.scm.bill.domain.sop.enums.NodeCode;
import com.scione.scm.bill.domain.sop.SopReminderDispatchAttemptRepository;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.SopReminderDispatchAttemptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 催办轮次执行记录的 MyBatis 适配器。
 * SQL 全部在 SopReminderDispatchAttemptMapper.xml，本层只做领域语义转换。
 */
@Repository
@RequiredArgsConstructor
public class MybatisSopReminderDispatchAttemptRepository implements SopReminderDispatchAttemptRepository {

    /** 补偿转人工时写入的状态，补偿扫描不再命中。 */
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_GIVE_UP = "GIVE_UP";

    /**
     * RUNNING 悬挂判定：单轮最长耗时约 3 分钟（含领星分页与发送前复核），
     * 放宽到 15 分钟才不会误判正在跑的轮次，同时能在进程被强杀后的一次补偿周期内接管。
     */
    private static final long STALE_RUNNING_MINUTES = 15;

    private final SopReminderDispatchAttemptMapper mapper;

    @Override
    public void begin(List<NodeCode> nodeCodes, LocalDateTime scheduledAt, LocalDateTime startedAt) {
        mapper.upsertRunning(NodeCode.join(nodeCodes), scheduledAt, startedAt);
    }

    @Override
    public void succeed(List<NodeCode> nodeCodes, LocalDateTime scheduledAt, int todos, int pushes,
                        int unresolvedCount, LocalDateTime nextRetryAt) {
        mapper.markSucceeded(NodeCode.join(nodeCodes), scheduledAt, todos, pushes, unresolvedCount,
                nextRetryAt, LocalDateTime.now());
    }

    @Override
    public void fail(List<NodeCode> nodeCodes, LocalDateTime scheduledAt, String errorMessage,
                     LocalDateTime nextRetryAt, boolean giveUp) {
        mapper.markFailed(NodeCode.join(nodeCodes), scheduledAt,
                giveUp ? STATUS_GIVE_UP : STATUS_FAILED,
                truncate(errorMessage),
                giveUp ? null : nextRetryAt,
                LocalDateTime.now());
    }

    @Override
    public List<RetryAttempt> dueRetries(LocalDateTime now, int maxAttempts) {
        LocalDateTime staleBefore = now.minusMinutes(STALE_RUNNING_MINUTES);
        return mapper.selectDueRetries(now, maxAttempts, staleBefore, todayStart(now)).stream()
                .map(row -> new RetryAttempt(row.id(), NodeCode.parseList(row.nodeCodes()), row.scheduledAt(), row.attempts()))
                .toList();
    }

    @Override
    public boolean superseded(List<NodeCode> nodeCodes, LocalDateTime scheduledAt) {
        return mapper.countLaterAttempts(NodeCode.join(nodeCodes), scheduledAt, todayStart(now())) > 0;
    }

    @Override
    public void abandon(List<NodeCode> nodeCodes, LocalDateTime scheduledAt, String reason) {
        mapper.markFailed(NodeCode.join(nodeCodes), scheduledAt, STATUS_GIVE_UP, truncate(reason), null, now());
    }

    @Override
    public int purgeBefore(LocalDateTime scheduledBefore, int limit) {
        return mapper.deleteBefore(scheduledBefore, limit);
    }

    @Override
    public int countBefore(LocalDateTime scheduledBefore) {
        return mapper.countBefore(scheduledBefore);
    }

    /** 业务时区固定东八区，与定时任务、发送记录写入保持一致，不受数据库和服务器时区影响。 */
    private static LocalDateTime now() {
        return LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
    }

    private static LocalDateTime todayStart(LocalDateTime reference) {
        return reference.toLocalDate().atStartOfDay();
    }

    /** error_msg 列宽 500，异常栈拼接后可能超长，入库前截断。 */
    private static String truncate(String message) {
        if (message == null) return null;
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
