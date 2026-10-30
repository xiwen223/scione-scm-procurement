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
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.TransactionIsolationLevel;
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
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");//业务统一用北京时间

    private final SqlSessionFactory sqlSessionFactory;//主库会话工厂，直接开可重复读快照会话
    private final MySqlNamedLock namedLock;//同步锁（GET_LOCK/RELEASE_LOCK）
    private final SopReminderProperties properties;//Nacos配置：来源库、同步等待/时效参数
    private final ObjectMapper objectMapper;//同步单据原文JSON解析

    /** 读节点1需要的PP快照：杭州店铺范围内、待采购/部分采购的PP行。 */
    @Override
    public Snapshot readPoCreate(List<Long> shopIds, LocalDateTime now) {
        if (shopIds.isEmpty()) {
            throw unavailable("节点1杭州店铺范围为空，停止预览");//没有杭州店铺，无法查PP，停
        }
        return readPublishedSnapshot(now, (mapper, published) -> new Snapshot(
                published.taskId(), published.syncedAt(),
                List.copyOf(mapper.selectPoCreatePlans(shopIds, properties.getPpStartDate().atStartOfDay()))));//等同步、校验后查PP，组装成Snapshot(来源任务id、同步时间、PP行列表)
    }

    /** 读普通节点共享的完整来源数据包：单据原文+日志+合同+跟单。 */
    @Override
    public NodeData readNodeData(LocalDateTime now) {
        return readPublishedSnapshot(now, (mapper, published) ->
                new NodeData(published, documents(mapper), logs(mapper), contracts(mapper, null), followUps(mapper, null)));//等同步、校验后一次性读出全部数据，组装成NodeData
    }

    /** 发送前复核用的小范围读取：只读指定PO的合同/跟单/收货，不等同步锁。 */
    @Override
    public CompletionFacts readCompletionFacts(List<String> poSns) {
        if (poSns.isEmpty()) return new CompletionFacts(Map.of(), Map.of(), Map.of(), null);//没有PO，直接返回空包
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {//开一个可重复读的只读会话
            SopReminderSourceMapper mapper = session.getMapper(SopReminderSourceMapper.class);
            try {
                Map<String, ReceiptState> receipts = new LinkedHashMap<>();//收货状态，key=收货单号
                for (SopReminderSourceMapper.ReceiptRow row : mapper.selectReceipts(poSns)) {
                    receipts.put(row.orderSn(), new ReceiptState(row.poSn(), row.status()));//把收货行转成ReceiptState
                }
                return new CompletionFacts(contracts(mapper, poSns), followUps(mapper, poSns), receipts,
                        mapper.selectLatestSuccessCollectedAt());//组装：合同+跟单+收货+最近一次成功采集时间
            } finally {
                // 只读会话也必须结束，连接归还池之前释放一致性快照。
                session.rollback();
            }
        } catch (DataAccessException exception) {
            throw unavailable("发送前复核数据库读取失败");
        }
    }

    /** 读网页登录Cookie（供领星网页接口客户端用），不返回给前端、不记日志。 */
    @Override
    public String readSellerCookies() {
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            SopReminderSourceMapper mapper = session.getMapper(SopReminderSourceMapper.class);
            String cookies = mapper.selectLatestSellerCookies();//查最近一次登录任务的Cookie
            if (cookies == null) throw unavailable("缺少网页登录Cookie，请先运行同步项目的login任务");//没Cookie说明没登录
            return cookies;
        } catch (DataAccessException exception) {
            throw unavailable("读取店铺登录状态失败，请检查来源库");
        }
    }

    /** 统一入口：先等同步释放锁，再在同一可重复读快照里校验发布状态并读取业务数据。 */
    private <T> T readPublishedSnapshot(LocalDateTime now, SnapshotQuery<T> query) {
        Duration waitTimeout = properties.getSyncWaitTimeout();//同步等待上限（Nacos配的）
        if (waitTimeout == null || waitTimeout.isZero() || waitTimeout.isNegative()
                || waitTimeout.compareTo(Duration.ofHours(24)) > 0) {
            throw unavailable("同步等待时间必须大于0且不超过24小时");//等待时间配得不合理，停
        }
        long waitingSince = System.nanoTime();//记录开始等待的时刻，用于后面统计等了多久
        // 等待期间不建立快照：锁在独立连接上，拿到锁之后才开会话。
        try (var ignored = namedLock.acquire(SYNC_LOCK, waitTimeout, 1, Duration.ZERO,
                "等待同步超时，本次催办停止，不使用旧数据", "等待同步已中断，本次催办停止")) {//抢同步锁，抢不到就等Python同步释放
            long waitedMs = (System.nanoTime() - waitingSince) / 1_000_000;//算了等了多久
            if (waitedMs > 1000) {
                log.info("催办等待同步结束，已取得来源锁，开始校验并读取最新数据：waitMs={}", waitedMs);//等超过1秒才打日志，避免噪音
            }
            return readSnapshot(query);//拿到锁后，校验同步状态并读数据
        }
    }

    /** 拿到同步锁后：校验最新同步任务成功、来源进度一致、数据未过期，再读业务数据。 */
    private <T> T readSnapshot(SnapshotQuery<T> query) {
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {//开可重复读快照会话
            SopReminderSourceMapper mapper = session.getMapper(SopReminderSourceMapper.class);
            try {
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

    /** 读同步单据原文，把每行的raw_payload解析成JSON数组，按businessId归组。 */
    private Map<String, JsonNode> documents(SopReminderSourceMapper mapper) {
        Map<String, JsonNode> documents = new LinkedHashMap<>();
        for (SopReminderSourceMapper.DocumentRow row : mapper.selectDocuments()) {
            try {
                JsonNode raw = objectMapper.readTree(row.rawPayload());//把原始JSON字符串解析成树
                if (raw == null || !raw.isArray() || raw.isEmpty()) throw new IOException("empty");//必须是非空数组，否则数据损坏
                documents.put(row.businessId(), raw);
            } catch (IOException exception) {
                throw unavailable("同步单据原文结构不完整，停止催办");
            }
        }
        return documents;
    }

    /** 读单据操作日志，按businessId归组成"单号→日志列表"，供规则层解析状态变更。 */
    private Map<String, List<Event>> logs(SopReminderSourceMapper mapper) {
        Map<String, List<Event>> logs = new LinkedHashMap<>();
        for (SopReminderSourceMapper.BusinessLogRow row : mapper.selectBusinessLogs()) {
            logs.computeIfAbsent(row.businessId(), ignored -> new ArrayList<>()).add(new Event(
                    row.olTime(), row.olTypeText(), row.olDetail(), row.olUid(), row.realname()));//把日志行转成领域Event(时间/类型/详情/操作人UID/姓名)
        }
        return logs;
    }

    /** null表示读取全部，空列表不查库；同PO多份合同时按创建时间取最早。 */
    private Map<String, ActiveContract> contracts(SopReminderSourceMapper mapper, List<String> poSns) {
        if (poSns != null && poSns.isEmpty()) return Map.of();//空列表直接返回空，不查库
        Map<String, ActiveContract> result = new LinkedHashMap<>();
        for (ActiveContract contract : mapper.selectContracts(poSns)) {
            result.putIfAbsent(contract.poSn(), contract);//同PO多份合同只保留第一份(最早创建的)
        }
        return result;
    }

    /** 读跟单导入信息，按"PO|SKU"归组，供跟单节点拆分SKU用。 */
    private Map<String, DeliveryFollowUp> followUps(SopReminderSourceMapper mapper, List<String> poSns) {
        if (poSns != null && poSns.isEmpty()) return Map.of();
        Map<String, DeliveryFollowUp> result = new LinkedHashMap<>();
        for (DeliveryFollowUp follow : mapper.selectFollowUps(poSns)) {
            result.put(follow.orderSn() + "|" + follow.sku(), follow);//key是"PO单号|SKU"，唯一标识一条跟单记录
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
