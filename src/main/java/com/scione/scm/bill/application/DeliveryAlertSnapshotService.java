package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.vo.FilterOptionsVO;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.config.DeliveryAlertProperties;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertQuery;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertSummary;
import com.scione.scm.bill.domain.deliveryalert.DeliveryFollowUp;
import com.scione.scm.bill.domain.deliveryalert.enums.DeliveryAlertRiskLevelEnum;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.DeliveryAlertMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertExportRowPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertSnapshotPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertSnapshotSummaryPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跟单预警看板每日快照：刷新时跑一次聚合，页面只读快照表。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeliveryAlertSnapshotService {

    private static final int GROUP_LIMIT = 50_000;
    private static final int INSERT_BATCH_SIZE = 500;
    /** 每日刷新时刻，与 application.yml 的 snapshot-cron 默认值保持一致。 */
    private static final int REFRESH_HOUR = 8;
    private static final int REFRESH_MINUTE = 30;

    private final DeliveryAlertMapper mapper;
    private final DeliveryAlertProperties properties;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    /** 删除旧快照并整批写入新快照；失败回滚，保留旧数据。 */
    @Transactional
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            log.warn("跟单预警看板快照正在刷新，本次跳过");
            return;
        }
        try {
            doRefresh();
        } finally {
            refreshing.set(false);
        }
    }

    private void doRefresh() {
        long begin = System.currentTimeMillis();
        LocalDateTime snapshotAt = LocalDateTime.now();
        List<DeliveryAlertSnapshotPO> rows = buildGroupRows(snapshotAt);
        mapper.deleteSnapshot();
        for (int start = 0; start < rows.size(); start += INSERT_BATCH_SIZE) {
            mapper.insertSnapshotBatch(rows.subList(start, Math.min(start + INSERT_BATCH_SIZE, rows.size())));
        }
        log.info("跟单预警看板快照刷新完成：groups={} snapshot_at={} 耗时={}ms", rows.size(), snapshotAt,
                System.currentTimeMillis() - begin);
    }

    private List<DeliveryAlertSnapshotPO> buildGroupRows(LocalDateTime snapshotAt) {
        List<DeliveryAlertPO> groups = mapper.findForExport(properties.getStartDate(), null, null, null, null,
                null, null, null, null, null, null, GROUP_LIMIT + 1);
        if (groups.size() > GROUP_LIMIT) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "快照主事项超过" + GROUP_LIMIT + "条，刷新终止");
        }
        List<String> groupKeys = new ArrayList<>();
        for (DeliveryAlertPO group : groups) {
            groupKeys.add(group.getGroupKey());
        }
        Map<String, List<DeliveryFollowUp>> followUps = new HashMap<>();
        for (DeliveryFollowUp followUp : mapper.findFollowUps(groupKeys)) {
            followUps.computeIfAbsent(followUp.groupKey(), key -> new ArrayList<>()).add(followUp);
        }
        List<DeliveryAlertSnapshotPO> rows = new ArrayList<>(groups.size());
        for (DeliveryAlertPO group : groups) {
            rows.add(toSnapshot(group, followUps.getOrDefault(group.getGroupKey(), List.of()), snapshotAt));
        }
        return rows;
    }

    private static DeliveryAlertSnapshotPO toSnapshot(DeliveryAlertPO group, List<DeliveryFollowUp> followUps,
                                                      LocalDateTime snapshotAt) {
        DeliveryAlertSnapshotPO row = new DeliveryAlertSnapshotPO();
        row.setGroupKey(group.getGroupKey());
        row.setTypes(nullToEmpty(group.getType()));
        row.setPurchaseMode(group.getPurchaseMode());
        row.setProductName(group.getProductName());
        row.setSku(group.getSku());
        row.setSkuCount(group.getSkuCount());
        row.setOrders(group.getOrders());
        row.setPlans(group.getPlans());
        row.setOptNames(group.getOptNames());
        row.setSuppliers(group.getSuppliers());
        row.setWarehouses(group.getWarehouses());
        row.setStatusTexts(group.getStatusText());
        row.setFollowers(followerText(followUps));
        row.setQtyPlan(group.getQtyPlan());
        row.setQtyReady(group.getQtyReady());
        int riskHours = group.getRisk() == null ? 0 : group.getRisk();
        row.setRiskHours(riskHours);
        row.setRiskLevel(riskLevel(riskHours));
        row.setCreatedTime(group.getCreatedTime());
        row.setSnapshotAt(snapshotAt);
        return row;
    }

    private static String followerText(List<DeliveryFollowUp> followUps) {
        Set<String> names = new LinkedHashSet<>();
        for (DeliveryFollowUp followUp : followUps) {
            String name = followUp.followerName();
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return String.join(",", names);
    }

    private static String riskLevel(int riskHours) {
        return DeliveryAlertRiskLevelEnum.ofRiskHours(riskHours).getCode();
    }

    public PageResult<DeliveryAlertSnapshotPO> findPage(DeliveryAlertQuery query) {
        long total = mapper.countSnapshot(query.keyword(), query.types(), query.suppliers(), query.buyers(),
                query.warehouses(), query.followers(), query.riskLevels(), query.view(),
                query.createDateFrom(), query.createDateTo());
        List<DeliveryAlertSnapshotPO> records = mapper.findSnapshotPage(query.keyword(), query.types(),
                query.suppliers(), query.buyers(), query.warehouses(), query.followers(), query.riskLevels(),
                query.view(), query.createDateFrom(), query.createDateTo(), query.offset(), query.pageSize());
        log.info("跟单预警看板-列表：读快照表 delivery_alert_group，命中 {} 条，本页 {} 条（第 {} 页）",
                total, records.size(), query.pageNum());
        return PageResult.of(query.pageNum(), query.pageSize(), total, records);
    }

    public DeliveryAlertSummary summary(DeliveryAlertQuery query) {
        DeliveryAlertSnapshotSummaryPO summary = mapper.snapshotSummary(query.keyword(), query.types(),
                query.suppliers(), query.buyers(), query.warehouses(), query.followers(), query.riskLevels(),
                query.createDateFrom(), query.createDateTo());
        log.info("跟单预警看板-页签数量：读快照表 delivery_alert_group，总计 {} 条", summary.getTotalCount());
        return new DeliveryAlertSummary(summary.getTotalCount(), summary.getDoneCount(), summary.getNormalCount(),
                summary.getDueSoonCount(), summary.getOverdueCount(), summary.getManualCount(), summary.getKitCount());
    }

    public FilterOptionsVO filterOptions() {
        Set<String> suppliers = new TreeSet<>();
        Set<String> buyers = new TreeSet<>();
        Set<String> warehouses = new TreeSet<>();
        Set<String> followers = new TreeSet<>();
        List<DeliveryAlertSnapshotPO> rows = mapper.findSnapshotOptions();
        for (DeliveryAlertSnapshotPO row : rows) {
            addTokens(suppliers, row.getSuppliers());
            addTokens(buyers, row.getOptNames());
            addTokens(warehouses, row.getWarehouses());
            addTokens(followers, row.getFollowers());
        }
        log.info("跟单预警看板-筛选选项：读快照表 delivery_alert_group，扫描 {} 行，供应商 {} / 采购员 {} / 仓库 {} / 跟单员 {}",
                rows.size(), suppliers.size(), buyers.size(), warehouses.size(), followers.size());
        FilterOptionsVO dto = new FilterOptionsVO();
        dto.setSuppliers(new ArrayList<>(suppliers));
        dto.setBuyers(new ArrayList<>(buyers));
        dto.setWarehouses(new ArrayList<>(warehouses));
        dto.setFollowers(new ArrayList<>(followers));
        return dto;
    }

    public Map<String, String> findGroupTypes(DeliveryAlertQuery query) {
        List<DeliveryAlertSnapshotPO> groups = mapper.findSnapshotGroupTypes(query.keyword(), query.types(),
                query.suppliers(), query.buyers(), query.warehouses(), query.followers(), query.riskLevels(),
                query.view(), query.createDateFrom(), query.createDateTo(), GROUP_LIMIT + 1);
        if (groups.size() > GROUP_LIMIT) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "导出数据超过" + GROUP_LIMIT + "条，请缩小筛选范围后重试");
        }
        Map<String, String> types = new HashMap<>();
        for (DeliveryAlertSnapshotPO group : groups) {
            types.put(group.getGroupKey(), typeText(group.getTypes()));
        }
        return types;
    }

    /** 导出扁平行：明细实时读 lx_ 前缀表，范围由快照筛出的主事项决定。 */
    public List<DeliveryAlertExportRowPO> findExportRows(int limit) {
        LocalDate startDate = properties.getStartDate();
        long startedAt = System.currentTimeMillis();
        List<DeliveryAlertExportRowPO> rows = mapper.findExportRows(startDate, limit);
        log.info("跟单预警看板-导出明细：实时读 lx_purchase_order_item 等前缀表（startDate={}），返回 {} 行，耗时 {}ms",
                startDate, rows.size(), System.currentTimeMillis() - startedAt);
        return rows;
    }

    /**
     * 快照是否早于今天 8 点半。服务重启多次也只补刷一次，避免重启后长时间沿用旧截面。
     */
    public boolean isStale() {
        LocalDateTime snapshotAt = mapper.findSnapshotAt();
        if (snapshotAt == null) {
            return true;
        }
        return snapshotAt.isBefore(LocalDate.now().atTime(REFRESH_HOUR, REFRESH_MINUTE));
    }

    private static void addTokens(Set<String> target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }

    private static String typeText(String types) {
        if (types.contains("合并下单")) {
            return "合并下单";
        }
        if (types.contains("套装")) {
            return "套装";
        }
        return "单品";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
