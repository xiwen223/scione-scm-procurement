package com.scione.scm.bill.infrastructure.persistence.mybatis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.config.SopReminderProperties;
import com.scione.scm.bill.domain.deliveryalert.DeliveryFollowUp;
import com.scione.scm.bill.domain.sop.SopReminderPreview.Snapshot;
import com.scione.scm.bill.domain.sop.SopReminderSourceRepository;
import com.scione.scm.bill.infrastructure.persistence.MySqlNamedLock;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.SopReminderSourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 MyBatis 的催办来源仓储适配器。
 * 会话编排（同步锁、可重复读、库名校验、时效校验）留在这一层，SQL 全部在
 * {@code SopReminderSourceMapper.xml}，与其余模块的持久化风格一致。
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class MybatisSopReminderSourceRepository implements SopReminderSourceRepository {

    /** 与Python统一同步使用同一把锁；先等待同步释放，再读其最终状态和数据。 */
    private static final String SYNC_LOCK = "lingxing_hourly_reminder_sync";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ReminderSourceSessions sessions;
    private final MySqlNamedLock namedLock;
    private final SopReminderProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public Snapshot readPoCreate(List<Long> shopIds, LocalDateTime now) {
        if (shopIds.isEmpty()) {
            throw unavailable("节点1杭州店铺范围为空，停止预览");
        }
        return readPublishedSnapshot(now, (mapper, published) -> new Snapshot(
                published.taskId(), published.syncedAt(),
                List.copyOf(mapper.selectPoCreatePlans(shopIds, properties.getPpStartDate().atStartOfDay()))));
    }

    @Override
    public NodeData readNodeData(LocalDateTime now) {
        return readPublishedSnapshot(now, (mapper, published) ->
                new NodeData(published, documents(mapper), logs(mapper), contracts(mapper, null), followUps(mapper, null)));
    }

    @Override
    public CompletionFacts readCompletionFacts(List<String> poSns) {
        if (poSns.isEmpty()) return new CompletionFacts(Map.of(), Map.of(), Map.of(), null);
        try (SqlSession session = sessions.openSnapshot()) {
            SopReminderSourceMapper mapper = session.getMapper(SopReminderSourceMapper.class);
            try {
                assertDatabase(mapper);
                Map<String, ReceiptState> receipts = new LinkedHashMap<>();
                for (SopReminderSourceMapper.ReceiptRow row : mapper.selectReceipts(poSns)) {
                    receipts.put(row.orderSn(), new ReceiptState(row.poSn(), row.status()));
                }
                return new CompletionFacts(contracts(mapper, poSns), followUps(mapper, poSns), receipts,
                        mapper.selectLatestSuccessCollectedAt());
            } finally {
                // 只读会话也必须结束，连接归还池之前释放一致性快照。
                session.rollback();
            }
        } catch (DataAccessException exception) {
            throw unavailable("发送前复核数据库读取失败");
        }
    }

    @Override
    public String readSellerCookies() {
        try (SqlSession session = sessions.openSnapshot()) {
            SopReminderSourceMapper mapper = session.getMapper(SopReminderSourceMapper.class);
            assertDatabase(mapper);
            String cookies = mapper.selectLatestSellerCookies();
            if (cookies == null) throw unavailable("缺少网页登录Cookie，请先运行同步项目的login任务");
            return cookies;
        } catch (DataAccessException exception) {
            throw unavailable("读取店铺登录状态失败，请检查来源库");
        }
    }

    /** 统一入口：先等同步释放锁，再在同一可重复读快照里校验发布状态并读取业务数据。 */
    private <T> T readPublishedSnapshot(LocalDateTime now, SnapshotQuery<T> query) {
        Duration waitTimeout = properties.getSyncWaitTimeout();
        if (waitTimeout == null || waitTimeout.isZero() || waitTimeout.isNegative()
                || waitTimeout.compareTo(Duration.ofHours(24)) > 0) {
            throw unavailable("同步等待时间必须大于0且不超过24小时");
        }
        long waitingSince = System.nanoTime();
        // 等待期间不建立快照：锁在独立连接上，拿到锁之后才开会话。
        try (var ignored = namedLock.acquire(SYNC_LOCK, waitTimeout, 1, Duration.ZERO,
                "等待同步超时，本次催办停止，不使用旧数据", "等待同步已中断，本次催办停止")) {
            long waitedMs = (System.nanoTime() - waitingSince) / 1_000_000;
            if (waitedMs > 1000) {
                log.info("催办等待同步结束，已取得来源锁，开始校验并读取最新数据：waitMs={}", waitedMs);
            }
            return readSnapshot(query);
        }
    }

    private <T> T readSnapshot(SnapshotQuery<T> query) {
        try (SqlSession session = sessions.openSnapshot()) {
            SopReminderSourceMapper mapper = session.getMapper(SopReminderSourceMapper.class);
            try {
                assertDatabase(mapper);
                SopReminderSourceMapper.SyncTaskRow task = mapper.selectLatestIncrementalTask();
                // 取得锁后检查最新一轮，不能跳过失败记录去找更早的成功记录。
                if (task == null || !"incremental".equals(task.taskType()) || !"success".equals(task.status())
                        || (task.errorMsg() != null && !task.errorMsg().isBlank())
                        || task.startTime() == null || task.endTime() == null) {
                    throw unavailable("最新一轮同步未成功完成，本次催办停止，不使用旧数据");
                }
                SopReminderSourceMapper.SyncStateRow state = mapper.selectSyncStateSummary();
                // 五个来源进度必须一致，且落在本轮任务开始和结束之间。
                if (state == null || state.sourceCount() != 5 || state.minUpdatedUntil() == null
                        || !state.minUpdatedUntil().equals(state.maxUpdatedUntil())
                        || state.minUpdatedUntil().isBefore(task.startTime())
                        || state.maxUpdatedUntil().isAfter(task.endTime())) {
                    throw unavailable("小时同步来源进度与成功任务不一致，停止催办预览");
                }
                // 等待可能持续数十分钟，时效必须按等待结束后的北京时间检查。
                LocalDateTime checkedAt = LocalDateTime.now(SHANGHAI);
                Duration maxAge = properties.getMaxSyncAge();
                // 从开始采集时计龄，避免耗时两小时的同步刚完成就被算作新鲜。
                if (maxAge == null || maxAge.isNegative() || maxAge.isZero()
                        || task.endTime().isAfter(checkedAt)
                        || task.startTime().isAfter(task.endTime())
                        || task.startTime().isBefore(checkedAt.minus(maxAge))) {
                    throw unavailable("催办来源数据已过期，停止催办预览");
                }
                return query.read(mapper, new PublishedSource(task.id(), task.endTime()));
            } finally {
                // 只读事务也必须结束；不写任何数据。
                session.rollback();
            }
        } catch (DataAccessException exception) {
            throw unavailable("催办来源库读取失败，请检查数据源和表结构");
        }
    }

    private void assertDatabase(SopReminderSourceMapper mapper) {
        String actual = mapper.selectCurrentDatabase();
        if (!properties.getSource().getExpectedDatabase().equals(actual)) {
            throw unavailable("催办实际数据库与配置不一致，停止查询");
        }
    }

    private Map<String, JsonNode> documents(SopReminderSourceMapper mapper) {
        Map<String, JsonNode> documents = new LinkedHashMap<>();
        for (SopReminderSourceMapper.DocumentRow row : mapper.selectDocuments()) {
            try {
                JsonNode raw = objectMapper.readTree(row.rawPayload());
                if (raw == null || !raw.isArray() || raw.isEmpty()) throw new IOException("empty");
                documents.put(row.businessId(), raw);
            } catch (IOException exception) {
                throw unavailable("同步单据原文结构不完整，停止催办");
            }
        }
        return documents;
    }

    private Map<String, List<Event>> logs(SopReminderSourceMapper mapper) {
        Map<String, List<Event>> logs = new LinkedHashMap<>();
        for (SopReminderSourceMapper.BusinessLogRow row : mapper.selectBusinessLogs()) {
            logs.computeIfAbsent(row.businessId(), ignored -> new ArrayList<>()).add(new Event(
                    row.olTime(), row.olTypeText(), row.olDetail(), row.olUid(), row.realname()));
        }
        return logs;
    }

    /** null表示读取全部，空列表不查库；同PO多份合同时按创建时间取最早。 */
    private Map<String, ActiveContract> contracts(SopReminderSourceMapper mapper, List<String> poSns) {
        if (poSns != null && poSns.isEmpty()) return Map.of();
        Map<String, ActiveContract> result = new LinkedHashMap<>();
        for (ActiveContract contract : mapper.selectContracts(poSns)) {
            result.putIfAbsent(contract.poSn(), contract);
        }
        return result;
    }

    private Map<String, DeliveryFollowUp> followUps(SopReminderSourceMapper mapper, List<String> poSns) {
        if (poSns != null && poSns.isEmpty()) return Map.of();
        Map<String, DeliveryFollowUp> result = new LinkedHashMap<>();
        for (DeliveryFollowUp follow : mapper.selectFollowUps(poSns)) {
            result.put(follow.orderSn() + "|" + follow.sku(), follow);
        }
        return result;
    }

    /** 已发布快照的查询体：只拿到 Mapper 和来源标识，不接触连接与事务。 */
    @FunctionalInterface
    private interface SnapshotQuery<T> {
        T read(SopReminderSourceMapper mapper, PublishedSource published);
    }

    private static BusinessException unavailable(String message) {
        return new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, message);
    }
}
