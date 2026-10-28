package com.scione.scm.bill.infrastructure.persistence.mybatis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.sop.SopReminderPreview.Candidate;
import com.scione.scm.bill.domain.sop.SopReminderRepository;
import com.scione.scm.bill.domain.sop.SopReminderLinkView.ReasonItem;
import com.scione.scm.bill.infrastructure.persistence.MySqlNamedLock;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.SopReminderMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ReminderPushBatchPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于 MyBatis 的催办仓储适配器：待办、外链、原因历史及推送批次。
 * 事务边界留在这一层，SQL 全部由 {@link SopReminderMapper} 的 XML 实现。
 */
@Repository
@RequiredArgsConstructor
public class MybatisSopReminderRepository implements SopReminderRepository {

    /** 不再区分业务轮次：同一单号在同节点同责任人只保留一条待办，催办次数按单号累计。 */
    private static final String ROUND_KEY = "ONCE";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String DISPATCH_LOCK = "scm_reminder_dispatch";
    private static final String PACKING_DISPATCH_LOCK = "scm_reminder_dispatch_packing";
    /**
     * 等待其它催办释放发送锁的上限。
     * 单轮催办实测：装箱约60秒，普通节点含核验约2~3分钟；超过5分钟说明对方已卡死，
     * 继续等下去只会占着线程和前端连接，不如快速失败、下一轮再来。
     */
    private static final Duration DISPATCH_WAIT_TIMEOUT = Duration.ofMinutes(5);
    /**
     * 发送结果未知（UNKNOWN）最多自动重发几次。
     * UNKNOWN 只代表没拿到回执、可能已经送达，重发有重复打扰风险；
     * 这里按「宁可重复也不漏发」取舍，封顶 3 次后转人工核对。
     */
    private static final int UNKNOWN_RETRY_LIMIT = 3;
    private static final int LOOKUP_CHUNK = 500;
    private static final int WRITE_CHUNK = 200;

    private final SopReminderMapper mapper;
    private final MySqlNamedLock namedLock;
    private final ObjectMapper objectMapper;

    @Override
    public void recordSkippedRecipients(String nodeCode, LocalDateTime scheduledAt, List<SkippedRecipient> rows) {
        if (rows.isEmpty()) return;
        for (int offset = 0; offset < rows.size(); offset += WRITE_CHUNK) {
            List<SkippedRecipient> chunk = rows.subList(offset, Math.min(offset + WRITE_CHUNK, rows.size()));
            List<SopReminderMapper.SkippedRow> inserts = new ArrayList<>(chunk.size());
            for (SkippedRecipient row : chunk) {
                String material;
                try {
                    material = objectMapper.writeValueAsString(Arrays.asList(
                            nodeCode, scheduledAt.toString(), row.businessSn(), row.receiverKey(),
                            row.errorCode(), row.itemKey(), row.stage()));
                } catch (JsonProcessingException exception) {
                    throw unavailable("未发送记录标识生成失败，停止本轮催办");
                }
                inserts.add(new SopReminderMapper.SkippedRow(scheduledAt, nodeCode, row.businessSn(),
                        row.receiverKey(), row.errorCode(), row.errorMessage(), sha256(material)));
            }
            mapper.insertSkippedRows(inserts);
        }
    }

    @Override
    public <T> T withDispatchLock(boolean packing, Supplier<T> action) {
        String lockName = packing ? PACKING_DISPATCH_LOCK : DISPATCH_LOCK;
        try (var ignored = namedLock.acquire(lockName, DISPATCH_WAIT_TIMEOUT, 0, Duration.ofMillis(500),
                "等待其他催办发送超过" + DISPATCH_WAIT_TIMEOUT.toMinutes() + "分钟，本次停止，请检查任务状态",
                "等待催办发送锁已中断")) {
            return action.get();
        }
    }

    @Override
    @Transactional
    public PreparedGroup prepareGroup(String nodeCode, String userid, List<Candidate> candidates) {
        boolean packing = "PACKING_RELATE".equals(nodeCode);
        String token = tokenFor(userid, packing);
        String round = ROUND_KEY + "@" + recipientHash(userid);
        for (int offset = 0; offset < candidates.size(); offset += WRITE_CHUNK) {
            List<Candidate> chunk = candidates.subList(offset, Math.min(offset + WRITE_CHUNK, candidates.size()));
            List<SopReminderMapper.TodoInsert> inserts = new ArrayList<>(chunk.size());
            for (Candidate candidate : chunk) {
                inserts.add(new SopReminderMapper.TodoInsert(nodeCode, candidate.businessType(),
                        candidate.businessSn(), candidate.itemKey(), round, candidate.ppCreatedAt(), userid, token));
            }
            mapper.upsertTodos(inserts);
        }
        Map<Key, Long> found = new LinkedHashMap<>();
        for (SopReminderMapper.TodoKey row : mapper.findTodoKeys(nodeCode, round)) {
            found.put(new Key(row.businessType(), row.businessSn(), row.itemKey()), row.id());
        }
        List<Long> ids = candidates.stream().map(candidate -> {
            Long id = found.get(new Key(candidate.businessType(), candidate.businessSn(), candidate.itemKey()));
            if (id == null) throw unavailable("批量保存待办后未找到对应记录，停止发送");
            return id;
        }).toList();
        mapper.refreshTodoTokens(token, userid, packing);
        return new PreparedGroup(token, List.copyOf(ids), candidates.stream().map(Candidate::stage).toList());
    }

    @Override
    public List<DispatchDecision> dispatchDecisions(List<Long> ids, List<Integer> stages,
                                                   LocalDateTime scheduledAt, boolean goodsReady) {
        if (ids.size() != stages.size()) throw unavailable("待办阶段与数量不一致");
        Map<Long, DispatchHistory> history = dispatchHistory(ids, scheduledAt);
        List<DispatchDecision> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            DispatchHistory h = history.getOrDefault(ids.get(i), new DispatchHistory(0, 0, false, false, 0, false, false));
            int unknown = h.unknownCount();
            if (goodsReady && (h.successes() >= 2 || h.highestSuccessfulStage() >= stages.get(i)))
                result.add(new DispatchDecision(false, true, "本阶段已成功催办或成功总次数已达两次", unknown));
            else if (goodsReady && h.unresolved())
                result.add(new DispatchDecision(false, true, "存在发送中或结果未知的记录，需先核对结果", unknown));
            else if (h.sending())
                result.add(new DispatchDecision(false, false, "上一批仍在发送中，等待结果确认", unknown));
            else if (h.slotBlocked() || h.retryPending())
                result.add(new DispatchDecision(false, false, "当前时点已发送、结果待确认或已有受控重试计划", unknown));
            // UNKNOWN 允许重发：send_status=4 只代表没拿到回执，可能已送达；宁可重复也不漏发，但最多3次。
            else if (unknown >= UNKNOWN_RETRY_LIMIT)
                result.add(new DispatchDecision(false, true,
                        "发送结果已连续" + UNKNOWN_RETRY_LIMIT + "次未知，不再自动重发，请人工核对", unknown));
            else result.add(new DispatchDecision(true, false, "", unknown));
        }
        return result;
    }

    @Override
    public List<Long> newPackingTodoIds(List<Long> ids, LocalDateTime scheduledAt) {
        Map<Long, DispatchHistory> history = dispatchHistory(ids, scheduledAt);
        return ids.stream().filter(id -> {
            DispatchHistory h = history.get(id);
            // 与普通节点一致：UNKNOWN 未超次数仍算新增，否则这条待办永远等不到重发。
            return h == null || (h.successes() == 0 && !h.sending() && !h.retryPending()
                    && h.unknownCount() < UNKNOWN_RETRY_LIMIT);
        }).toList();
    }

    @Override
    public void suppressGoodsReadyTodos(String userid, List<Long> ids) {
        for (int offset = 0; offset < ids.size(); offset += LOOKUP_CHUNK) {
            mapper.suppressGoodsReadyTodos(userid, ids.subList(offset, Math.min(offset + LOOKUP_CHUNK, ids.size())));
        }
    }

    @Override
    public boolean retryWithinGoodsReadyQuota(long batchId) {
        return mapper.countGoodsReadyQuotaBlocked(batchId) == 0;
    }

    @Override
    public void cancelMissingRfStartTodos(List<String> businessSns) {
        for (int offset = 0; offset < businessSns.size(); offset += LOOKUP_CHUNK) {
            mapper.cancelMissingRfStartTodos(
                    businessSns.subList(offset, Math.min(offset + LOOKUP_CHUNK, businessSns.size())));
        }
    }

    @Override
    public void closeVerifiedTodos(String nodeCode, String userid, List<Long> ids) {
        for (int offset = 0; offset < ids.size(); offset += LOOKUP_CHUNK) {
            mapper.closeVerifiedTodos(nodeCode, userid,
                    ids.subList(offset, Math.min(offset + LOOKUP_CHUNK, ids.size())));
        }
    }

    @Override
    public void closeAbsentTodos(String nodeCode, List<Long> activeIds, List<String> uncertainSns) {
        mapper.closeAbsentTodos(nodeCode, activeIds, uncertainSns);
    }

    @Override
    @Transactional
    public long startPush(String userid, List<Long> todoIds, List<Integer> stages,
                          LocalDateTime scheduledAt, String contextJson) {
        if (todoIds.size() != stages.size()) throw unavailable("待办阶段与数量不一致");
        ReminderPushBatchPO batch = new ReminderPushBatchPO();
        batch.setScheduledAt(scheduledAt);
        batch.setRecipientUserid(userid);
        batch.setSentAt(LocalDateTime.now(SHANGHAI));
        batch.setSendResult(contextJson);
        mapper.insertPushBatch(batch);
        if (batch.getId() == null) throw unavailable("推送批次未返回主键");
        long batchId = batch.getId();
        for (int offset = 0; offset < todoIds.size(); offset += WRITE_CHUNK) {
            int end = Math.min(offset + WRITE_CHUNK, todoIds.size());
            List<SopReminderMapper.PushDetailInsert> details = new ArrayList<>(end - offset);
            for (int i = offset; i < end; i++) {
                details.add(new SopReminderMapper.PushDetailInsert(todoIds.get(i), stages.get(i), scheduledAt));
            }
            mapper.insertPushDetails(batchId, details);
        }
        return batchId;
    }

    @Override
    public void finishPush(long batchId, int sendStatus, String resultJson, LocalDateTime nextRetryAt) {
        if (mapper.finishPush(batchId, sendStatus, resultJson, nextRetryAt) != 1) {
            throw unavailable("推送结果未能确认保存，请核查该批次，不要直接重发");
        }
    }

    @Override
    public List<Long> staleSending(boolean packing, LocalDateTime before) {
        return mapper.findStaleSending(before, packing);
    }

    @Override
    public boolean markSendingUnknown(long id, boolean packing, LocalDateTime before, String outcomeJson) {
        return mapper.markSendingUnknown(id, before, packing, outcomeJson) == 1;
    }

    @Override
    public List<RetryBatch> dueRetries(LocalDateTime now, boolean packing) {
        return mapper.findDueRetries(now, packing);
    }

    @Override
    public boolean claimRetry(long id, String contextJson, LocalDateTime now) {
        return mapper.claimRetry(id, contextJson, now) == 1;
    }

    @Override
    public void stopRetry(long id) {
        mapper.stopRetry(id);
    }

    @Override
    public List<RetryTodo> retryTodos(long id, String userid) {
        return mapper.findRetryTodos(id, userid);
    }

    @Override
    public boolean matchesRetry(Candidate candidate, RetryTodo todo, String userid) {
        return candidate.businessSn().equals(todo.businessSn())
                && candidate.itemKey().equals(todo.itemKey())
                && candidate.stage() == todo.stage()
                && (ROUND_KEY + "@" + recipientHash(userid)).equals(todo.roundKey());
    }

    @Override
    public int retryTodoCount(long id) {
        return mapper.countRetryTodos(id);
    }

    @Override
    public Link findLink(String token) {
        SopReminderMapper.LinkBatch batch = mapper.findLinkBatch(token);
        if (batch != null) return new Link(token, batch.id(), batch.recipientUserid(), batch.sentAt());
        String assignee = mapper.findAssigneeByToken(token);
        if (assignee == null) throw new BusinessException(ResultCode.REMINDER_LINK_NOT_FOUND);
        return new Link(token, null, assignee, null);
    }

    @Override
    public List<TodoRow> loadTodos(Link link) {
        // 有批次快照按消息明细读，待办后来被关闭也不影响这条链接展示的内容；否则老token兜底实时读。
        if (link.batchId() != null) return mapper.findSnapshotTodos(link.batchId(), link.sentAt());
        return mapper.findActiveTodos(link.token());
    }

    @Override
    public Map<Long, List<ReasonItem>> loadReasons(List<Long> todoIds) {
        Map<Long, List<ReasonItem>> result = new LinkedHashMap<>();
        if (todoIds.isEmpty()) return result;
        for (int offset = 0; offset < todoIds.size(); offset += LOOKUP_CHUNK) {
            List<Long> chunk = todoIds.subList(offset, Math.min(offset + LOOKUP_CHUNK, todoIds.size()));
            for (SopReminderMapper.ReasonRow row : mapper.findReasons(chunk)) {
                result.computeIfAbsent(row.todoId(), ignored -> new ArrayList<>())
                        .add(new ReasonItem(row.reason(), row.createdAt()));
            }
        }
        return result;
    }

    @Override
    public void addReason(String token, long todoId, String reason) {
        if (mapper.insertReason(token, todoId, reason) != 1) {
            throw new BusinessException(ResultCode.REMINDER_LINK_NOT_FOUND, "待办不存在、已结束或不属于当前链接");
        }
    }

    @Override
    public int purgeClosedTodoDetails(LocalDateTime closedBefore, int limit) {
        return mapper.deleteClosedTodoDetails(closedBefore, limit);
    }

    @Override
    public int purgeSkippedRecords(LocalDateTime scheduledBefore, int limit) {
        return mapper.deleteSkippedRecords(scheduledBefore, limit);
    }

    @Override
    public int countClosedTodoDetails(LocalDateTime closedBefore) {
        return mapper.countClosedTodoDetails(closedBefore);
    }

    @Override
    public int countSkippedRecords(LocalDateTime scheduledBefore) {
        return mapper.countSkippedRecords(scheduledBefore);
    }

    /** 同一责任人按普通催办/关联货件分别保持稳定链接，不按自然日重建历史。 */
    private String tokenFor(String userid, boolean packing) {
        List<String> tokens = mapper.findStableTokens(userid, packing);
        return tokens.isEmpty() ? UUID.randomUUID().toString().replace("-", "") : tokens.get(0);
    }

    private Map<Long, DispatchHistory> dispatchHistory(List<Long> ids, LocalDateTime scheduledAt) {
        Map<Long, DispatchHistory> result = new LinkedHashMap<>();
        String now = LocalDateTime.now(SHANGHAI).toString();
        for (int offset = 0; offset < ids.size(); offset += LOOKUP_CHUNK) {
            List<Long> chunk = ids.subList(offset, Math.min(offset + LOOKUP_CHUNK, ids.size()));
            for (SopReminderMapper.DispatchHistoryRow row : mapper.findDispatchHistory(chunk, scheduledAt, now)) {
                result.put(row.todoId(), new DispatchHistory(row.successes(), row.highestStage(),
                        row.slotBlocked(), row.sending(), row.unknownCount(),
                        row.unresolved(), row.retryPending()));
            }
        }
        return result;
    }

    private record Key(String type, String sn, String item) { }

    private record DispatchHistory(int successes, int highestSuccessfulStage, boolean slotBlocked,
                                   boolean sending, int unknownCount,
                                   boolean unresolved, boolean retryPending) { }

    private static String recipientHash(String userid) {
        return sha256(userid).substring(0, 16);
    }

    private static String sha256(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String trim(String value, int maximum) {
        return value == null ? null : value.substring(0, Math.min(value.length(), maximum));
    }

    private static BusinessException unavailable(String message) {
        return new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, message);
    }
}
