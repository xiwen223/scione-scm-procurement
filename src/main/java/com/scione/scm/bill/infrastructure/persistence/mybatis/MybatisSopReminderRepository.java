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
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");//业务统一用北京时间
    private static final String DISPATCH_LOCK = "scm_reminder_dispatch";//普通节点发送锁
    private static final String PACKING_DISPATCH_LOCK = "scm_reminder_dispatch_packing";//装箱发送锁（与普通分开，避免互相阻塞）
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
    private static final int LOOKUP_CHUNK = 500;//批量查询的每批大小（查历史/查待办）
    private static final int WRITE_CHUNK = 200;//批量写入的每批大小（建待办/写明细）

    private final SopReminderMapper mapper;
    private final MySqlNamedLock namedLock;//发送锁（GET_LOCK/RELEASE_LOCK）
    private final ObjectMapper objectMapper;//上下文/结果JSON序列化

    /** 写「因收件人缺失/无效而跳过」的记录（record_type=3），不关联推送明细，只用于事后排查。 */
    @Override
    public void recordSkippedRecipients(String nodeCode, LocalDateTime scheduledAt, List<SkippedRecipient> rows) {
        if (rows.isEmpty()) return;//没有跳过记录，直接返回
        for (int offset = 0; offset < rows.size(); offset += WRITE_CHUNK) {//每200条一批写入
            List<SkippedRecipient> chunk = rows.subList(offset, Math.min(offset + WRITE_CHUNK, rows.size()));//当前这批
            List<SopReminderMapper.SkippedRow> inserts = new ArrayList<>(chunk.size());//要插入的SkipRow列表
            for (SkippedRecipient row : chunk) {
                String material;//用于生成去重指纹的原始材料
                try {
                    material = objectMapper.writeValueAsString(Arrays.asList(
                            nodeCode, scheduledAt.toString(), row.businessSn(), row.receiverKey(),
                            row.errorCode(), row.itemKey(), row.stage()));//把这条跳过的关键信息拼成JSON字符串
                } catch (JsonProcessingException exception) {
                    throw unavailable("未发送记录标识生成失败，停止本轮催办");
                }
                inserts.add(new SopReminderMapper.SkippedRow(scheduledAt, nodeCode, row.businessSn(),
                        row.receiverKey(), row.errorCode(), row.errorMessage(), sha256(material)));//构造SkipRow，指纹用material的SHA-256
            }
            mapper.insertSkippedRows(inserts);//批量插入
        }
    }

    /** 加发送锁执行：装箱和普通各拿各的锁，锁在独立连接上，不随业务事务提交。 */
    @Override
    public <T> T withDispatchLock(boolean packing, Supplier<T> action) {
        String lockName = packing ? PACKING_DISPATCH_LOCK : DISPATCH_LOCK;//装箱走装箱锁，普通走普通锁
        try (var ignored = namedLock.acquire(lockName, DISPATCH_WAIT_TIMEOUT, 0, Duration.ofMillis(500),
                "等待其他催办发送超过" + DISPATCH_WAIT_TIMEOUT.toMinutes() + "分钟，本次停止，请检查任务状态",
                "等待催办发送锁已中断")) {//获取锁，最多等5分钟
            return action.get();//拿到锁后执行真正的发送动作
        }
    }

    /** 建待办：把候选按同一责任人批量 upsert 成待办，回查拿到 todoId，并刷新该责任人的稳定 token。 */
    @Override
    @Transactional
    public PreparedGroup prepareGroup(String nodeCode, String userid, List<Candidate> candidates) {
        boolean packing = "PACKING_RELATE".equals(nodeCode);//是不是装箱节点
        String token = tokenFor(userid, packing);//取这个责任人的稳定token（没有就新建一个）
        String round = ROUND_KEY + "@" + recipientHash(userid);//本轮的唯一键=固定前缀+收件人哈希
        // 分批 upsert 待办，避免单次 SQL 参数过多。
        for (int offset = 0; offset < candidates.size(); offset += WRITE_CHUNK) {//每200个候选一批
            List<Candidate> chunk = candidates.subList(offset, Math.min(offset + WRITE_CHUNK, candidates.size()));//当前这批候选
            List<SopReminderMapper.TodoInsert> inserts = new ArrayList<>(chunk.size());//要插入的待办列表
            for (Candidate candidate : chunk) {
                inserts.add(new SopReminderMapper.TodoInsert(nodeCode, candidate.businessType(),
                        candidate.businessSn(), candidate.itemKey(), round, candidate.ppCreatedAt(), userid, token));//构造TodoInsert（节点/类型/单号/明细键/轮次键/起算时间/收件人/token）
            }
            mapper.upsertTodos(inserts);//批量upsert（存在则更新，不存在则插入）
        }
        // 回查刚写入的待办 id，保证后续发送、去重、关闭都能精确落到具体待办上。
        Map<Key, Long> found = new LinkedHashMap<>();//key=待办定位键，value=待办id
        for (SopReminderMapper.TodoKey row : mapper.findTodoKeys(nodeCode, round)) {
            found.put(new Key(row.businessType(), row.businessSn(), row.itemKey()), row.id());//把查回来的待办键和id存进map
        }
        List<Long> ids = candidates.stream().map(candidate -> {
            Long id = found.get(new Key(candidate.businessType(), candidate.businessSn(), candidate.itemKey()));//按候选的定位键找回查到的id
            if (id == null) throw unavailable("批量保存待办后未找到对应记录，停止发送");//回查不到，说明写入有问题，停
            return id;
        }).toList();
        mapper.refreshTodoTokens(token, userid, packing);//刷新该责任人的稳定token
        return new PreparedGroup(token, List.copyOf(ids), candidates.stream().map(Candidate::stage).toList());//返回token、待办id列表、阶段列表
    }

    /** 发送去重判定：按待办历史（成功次数/在途/时点占用/未知次数）决定本条该发、隐藏还是等待。 */
    @Override
    public List<DispatchDecision> dispatchDecisions(List<Long> ids, List<Integer> stages,
                                                   LocalDateTime scheduledAt, boolean goodsReady) {
        if (ids.size() != stages.size()) throw unavailable("待办阶段与数量不一致");
        Map<Long, DispatchHistory> history = dispatchHistory(ids, scheduledAt);
        List<DispatchDecision> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            DispatchHistory h = history.getOrDefault(ids.get(i), new DispatchHistory(0, 0, false, false, 0, false, false));
            int unknown = h.unknownCount();
            // 货物备齐节点按阶段和总次数双上限去重，其余节点走下面的通用判断。
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

    /** 发送前落库：先写消息批次（record_type=1）拿到主键，再按批次写待办明细（record_type=2）。 */
    @Override
    @Transactional
    public long startPush(String userid, List<Long> todoIds, List<Integer> stages,
                          LocalDateTime scheduledAt, String contextJson) {
        if (todoIds.size() != stages.size()) throw unavailable("待办阶段与数量不一致");//待办数和阶段数必须一一对应
        ReminderPushBatchPO batch = new ReminderPushBatchPO();//新建批次对象
        batch.setScheduledAt(scheduledAt);//计划时点
        batch.setRecipientUserid(userid);//收件人
        batch.setSentAt(LocalDateTime.now(SHANGHAI));//发出时间（用北京时间）
        batch.setSendResult(contextJson);//消息上下文JSON
        mapper.insertPushBatch(batch);//插入批次行
        if (batch.getId() == null) throw unavailable("推送批次未返回主键");//没拿到自增主键说明插入失败
        long batchId = batch.getId();//批次主键，明细要挂它下面
        // 明细分批写入，绑定到上面的批次主键。
        for (int offset = 0; offset < todoIds.size(); offset += WRITE_CHUNK) {//每200条待办一批写明细
            int end = Math.min(offset + WRITE_CHUNK, todoIds.size());//本批结束位置
            List<SopReminderMapper.PushDetailInsert> details = new ArrayList<>(end - offset);//本批明细
            for (int i = offset; i < end; i++) {
                details.add(new SopReminderMapper.PushDetailInsert(todoIds.get(i), stages.get(i), scheduledAt));//每条明细=待办id+阶段+时点
            }
            mapper.insertPushDetails(batchId, details);//批量插入明细
        }
        return batchId;//返回批次主键
    }

    @Override
    public void finishPush(long batchId, int sendStatus, String resultJson, LocalDateTime nextRetryAt) {
        if (mapper.finishPush(batchId, sendStatus, resultJson, nextRetryAt) != 1) {
            throw unavailable("推送结果未能确认保存，请核查该批次，不要直接重发");//更新结果影响行数不是1，说明批次不存在或状态不对，停
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

    /** 解析外链token：先按消息批次快照找，找不到再按老待办的稳定token兜底。 */
    @Override
    public Link findLink(String token) {
        SopReminderMapper.LinkBatch batch = mapper.findLinkBatch(token);//按token查消息批次（新版快照链接）
        if (batch != null) return new Link(token, batch.id(), batch.recipientUserid(), batch.sentAt());//有批次，返回快照链接（内容固定）
        String assignee = mapper.findAssigneeByToken(token);//按token查待办的收件人（老token兜底）
        if (assignee == null) throw new BusinessException(ResultCode.REMINDER_LINK_NOT_FOUND);//都找不到，token无效
        return new Link(token, null, assignee, null);//返回老token兜底链接（batchId为空，实时读）
    }

    /** 读外链该展示的待办：有批次快照读快照，没有就按待办实时状态读。 */
    @Override
    public List<TodoRow> loadTodos(Link link) {
        // 有批次快照按消息明细读，待办后来被关闭也不影响这条链接展示的内容；否则老token兜底实时读。
        if (link.batchId() != null) return mapper.findSnapshotTodos(link.batchId(), link.sentAt());//有快照，按发送时刻的明细读
        return mapper.findActiveTodos(link.token());//无快照，按待办实时状态读（status=0的）
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

    /** 批量查每个待办的历史推送情况（成功次数/最高成功阶段/在途/时点占用/未知次数），供去重判定。 */
    private Map<Long, DispatchHistory> dispatchHistory(List<Long> ids, LocalDateTime scheduledAt) {
        Map<Long, DispatchHistory> result = new LinkedHashMap<>();
        String now = LocalDateTime.now(SHANGHAI).toString();
        for (int offset = 0; offset < ids.size(); offset += LOOKUP_CHUNK) {//分批查，避免单次IN参数过多
            List<Long> chunk = ids.subList(offset, Math.min(offset + LOOKUP_CHUNK, ids.size()));
            for (SopReminderMapper.DispatchHistoryRow row : mapper.findDispatchHistory(chunk, scheduledAt, now)) {
                result.put(row.todoId(), new DispatchHistory(row.successes(), row.highestStage(),
                        row.slotBlocked(), row.sending(), row.unknownCount(),
                        row.unresolved(), row.retryPending()));//把SQL行转成领域历史对象
            }
        }
        return result;
    }

    /** 待办定位键：业务类型+单号+明细key（跟单节点是F:跟单员），唯一确定一条待办。 */
    private record Key(String type, String sn, String item) { }

    /** 一条待办的推送历史汇总，去重判定的输入。 */
    private record DispatchHistory(int successes, int highestSuccessfulStage, boolean slotBlocked,
                                   boolean sending, int unknownCount,
                                   boolean unresolved, boolean retryPending) { }

    /** 把企微userid哈希成16位短码，用作待办roundKey的一部分，避免直接把明文账号写进唯一键。 */
    private static String recipientHash(String userid) {
        return sha256(userid).substring(0, 16);
    }

    /** 对字符串做SHA-256，输出十六进制；用于生成稳定标识和去重指纹。 */
    private static String sha256(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 字符串截断到指定长度；null原样返回。 */
    private static String trim(String value, int maximum) {
        return value == null ? null : value.substring(0, Math.min(value.length(), maximum));
    }

    private static BusinessException unavailable(String message) {
        return new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, message);
    }
}
