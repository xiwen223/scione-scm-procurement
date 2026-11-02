package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.vo.DeliveryAlertComponentVO;
import com.scione.scm.bill.application.vo.DeliveryAlertDetailVO;
import com.scione.scm.bill.application.vo.DeliveryAlertListItemVO;
import com.scione.scm.bill.application.vo.DeliveryAlertSummaryVO;
import com.scione.scm.bill.application.vo.DeliveryAlertSyncTimeVO;
import com.scione.scm.bill.application.vo.FilterOptionsVO;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertQuery;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertRepository;
import com.scione.scm.bill.domain.deliveryalert.DeliveryFollowUp;
import com.scione.scm.bill.domain.deliveryalert.enums.DeliveryAlertRiskLevelEnum;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertSnapshotPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 采购交付预警看板查询应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryAlertAppService {

    private final DeliveryAlertSnapshotService snapshotService;
    private final DeliveryAlertRepository repository;

    public PageResult<DeliveryAlertListItemVO> findPage(DeliveryAlertQuery query) {
        PageResult<DeliveryAlertSnapshotPO> page = snapshotService.findPage(query);
        Map<String, List<DeliveryFollowUp>> followUps = groupByGroupKey(
                repository.findFollowUps(page.getRecords().stream()
                        .map(DeliveryAlertSnapshotPO::getGroupKey).toList()));
        log.info("跟单预警看板-跟单信息：实时读 reminder_excel_import，本页 {} 个主事项返回 {} 条跟单记录",
                page.getRecords().size(), followUps.values().stream().mapToInt(List::size).sum());
        return PageResult.of(query.pageNum(), query.pageSize(), page.getTotal(),
                page.getRecords().stream().map(record -> toListItem(record, followUps)).toList());
    }

    public DeliveryAlertSummaryVO summary(DeliveryAlertQuery query) {
        return DeliveryAlertSummaryVO.from(snapshotService.summary(query));
    }

    public DeliveryAlertSyncTimeVO lastSyncTime() {
        return DeliveryAlertSyncTimeVO.from(repository.findLastOrderSyncTime());
    }

    public FilterOptionsVO filterOptions() {
        return snapshotService.filterOptions();
    }

    public List<DeliveryAlertComponentVO> findComponents(String groupKey) {
        List<DeliveryFollowUp> followUps = repository.findFollowUps(List.of(groupKey));
        List<DeliveryAlertComponentVO> components = repository.findComponents(groupKey).stream()
                .map(record -> {
                    DeliveryAlertComponentVO dto = DeliveryAlertComponentVO.from(record);
                    dto.setFollowUps(followUps.stream()
                        .filter(row -> row.orderSn().equals(dto.getOrderSn()) && row.sku().equals(dto.getSku()))
                        .toList());
                    return dto;
                })
                .toList();
        log.info("跟单预警看板-抽屉明细：实时读 lx_ 前缀表，groupKey={} 明细 {} 条，跟单信息 {} 条", groupKey,
                components.size(), followUps.size());
        return components;
    }

    public DeliveryAlertDetailVO findDetail(String groupKey) {
        log.info("跟单预警看板-抽屉详情：实时读 lx_ 前缀表，groupKey={}", groupKey);
        return DeliveryAlertDetailVO.from(repository.findDetail(groupKey));
    }

    public DeliveryAlertDetailVO findComponentDetail(String groupKey, Long componentId) {
        log.info("跟单预警看板-单条明细：实时读 lx_ 前缀表，groupKey={} componentId={}", groupKey, componentId);
        return DeliveryAlertDetailVO.from(repository.findComponentDetail(groupKey, componentId));
    }

    private DeliveryAlertListItemVO toListItem(DeliveryAlertSnapshotPO record,
                                               Map<String, List<DeliveryFollowUp>> followUps) {
        List<String> types = split(record.getTypes());
        DeliveryAlertListItemVO dto = new DeliveryAlertListItemVO();
        dto.setGroupKey(record.getGroupKey());
        dto.setTypes(types);
        dto.setTypeDesc(String.join("/", types));
        dto.setPurchaseMode(record.getPurchaseMode());
        dto.setProductName(record.getProductName());
        dto.setSku(record.getSku());
        dto.setSkuCount(record.getSkuCount());
        dto.setOrderSns(split(record.getOrders()));
        dto.setPlanSns(split(record.getPlans()));
        dto.setOptNames(split(record.getOptNames()));
        dto.setSuppliers(split(record.getSuppliers()));
        dto.setWarehouses(split(record.getWarehouses()));
        dto.setStatusTexts(split(record.getStatusTexts()));
        dto.setQtyPlan(record.getQtyPlan());
        dto.setQtyReady(record.getQtyReady());
        dto.setRiskHours(record.getRiskHours());
        dto.setRiskLevel(record.getRiskLevel());
        dto.setRiskLevelDesc(riskLevelDesc(record.getRiskLevel()));
        dto.setCreatedTime(record.getCreatedTime());
        dto.setFollowUps(followUps.getOrDefault(record.getGroupKey(), List.of()));
        return dto;
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private static String riskLevelDesc(String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return "";
        }
        return DeliveryAlertRiskLevelEnum.of(riskLevel).getLabel();
    }

    private static Map<String, List<DeliveryFollowUp>> groupByGroupKey(List<DeliveryFollowUp> rows) {
        return rows.stream().collect(Collectors.groupingBy(DeliveryFollowUp::groupKey));
    }
}
