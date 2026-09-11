package com.scione.scm.bill.infrastructure.persistence.mybatis;

import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlert;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertComponent;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertDetail;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertEvent;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertPage;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertQuery;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertRepository;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertSummary;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.DeliveryAlertMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertComponentPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertDetailPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertSummaryPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryBusinessLogPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 基于 MyBatis 的采购交付预警看板只读仓储适配器。
 */
@Repository
@RequiredArgsConstructor
public class MybatisDeliveryAlertRepository implements DeliveryAlertRepository {

    private final DeliveryAlertMapper deliveryAlertMapper;

    @Override
    public DeliveryAlertPage findPage(DeliveryAlertQuery query) {
        long total = deliveryAlertMapper.count(query.startDate(), query.keyword(), query.supplier(),
                query.buyer(), query.warehouse(), query.type(), query.riskLevel(), query.view());
        List<DeliveryAlert> records = deliveryAlertMapper.findPage(query.startDate(), query.keyword(), query.supplier(),
                        query.buyer(), query.warehouse(), query.type(), query.riskLevel(), query.view(), query.offset(), query.pageSize())
                .stream()
                .map(this::toDomain)
                .toList();
        return new DeliveryAlertPage(total, records);
    }

    @Override
    public DeliveryAlertSummary summary(DeliveryAlertQuery query) {
        DeliveryAlertSummaryPO po = deliveryAlertMapper.summary(query.startDate(), query.keyword(),
                query.supplier(), query.buyer(), query.warehouse(), query.type(), query.riskLevel());
        return new DeliveryAlertSummary(po.getTotalCount(), po.getDoneCount(), po.getNormalCount(),
                po.getDueSoonCount(), po.getOverdueCount(), po.getManualCount(), po.getKitCount());
    }

    @Override
    public LocalDateTime findLastOrderSyncTime() {
        return deliveryAlertMapper.findLastOrderSyncTime();
    }

    @Override
    public List<String> findDistinctSuppliers(DeliveryAlertQuery query) {
        return deliveryAlertMapper.findDistinctSuppliers(query.startDate());
    }

    @Override
    public List<String> findDistinctBuyers(DeliveryAlertQuery query) {
        return deliveryAlertMapper.findDistinctBuyers(query.startDate());
    }

    @Override
    public List<String> findDistinctWarehouses(DeliveryAlertQuery query) {
        return deliveryAlertMapper.findDistinctWarehouses(query.startDate());
    }

    @Override
    public List<DeliveryAlertComponent> findComponents(String groupKey) {
        return deliveryAlertMapper.findComponents(groupKey).stream()
                .map(this::toComponentDomain)
                .toList();
    }

    @Override
    public DeliveryAlertDetail findDetail(String groupKey) {
        DeliveryAlertDetailPO meta = deliveryAlertMapper.findDetailMeta(groupKey);
        if (meta == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND);
        }
        String title = meta.getProductName() == null || meta.getProductName().isBlank()
                ? groupKey
                : meta.getProductName();
        return new DeliveryAlertDetail(
                groupKey,
                null,
                title,
                false,
                deliveryAlertMapper.findBusinessLogsByGroupKey(groupKey).stream()
                        .map(this::toEventDomain)
                        .toList());
    }

    @Override
    public DeliveryAlertDetail findComponentDetail(String groupKey, Long componentId) {
        DeliveryAlertComponentPO component = deliveryAlertMapper.findComponent(groupKey, componentId);
        if (component == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND);
        }
        String title = component.getProductName() == null || component.getProductName().isBlank()
                ? component.getSku()
                : component.getProductName();
        boolean kitComponent = !groupKey.equals(component.getOrderSn());
        return new DeliveryAlertDetail(
                groupKey,
                componentId,
                title,
                kitComponent,
                deliveryAlertMapper.findBusinessLogsByOrderSn(component.getOrderSn()).stream()
                        .map(this::toEventDomain)
                        .toList());
    }

    private DeliveryAlertEvent toEventDomain(DeliveryBusinessLogPO po) {
        return new DeliveryAlertEvent(
                po.getId(),
                po.getOrderSn(),
                po.getEventTime(),
                po.getTitle(),
                po.getDescription(),
                po.getOperator());
    }

    private DeliveryAlertComponent toComponentDomain(DeliveryAlertComponentPO po) {
        return new DeliveryAlertComponent(
                po.getComponentId(),
                po.getSku(),
                po.getProductName(),
                po.getOrderSn(),
                po.getPlanSn(),
                po.getPurchaseMode(),
                po.getSupplierName(),
                po.getWarehouseName(),
                po.getOptRealname(),
                po.getStatusText(),
                po.getExpectedQty(),
                po.getQuantityEntry(),
                po.getQualifiedQty(),
                po.getReturnedQty(),
                po.getExchangeQty(),
                po.getRisk());
    }

    private DeliveryAlert toDomain(DeliveryAlertPO po) {
        return new DeliveryAlert(
                po.getGroupKey(),
                split(po.getType()),
                po.getPurchaseMode(),
                po.getProductName(),
                po.getSku(),
                po.getSkuCount(),
                split(po.getOrders()),
                split(po.getPlans()),
                split(po.getOptNames()),
                split(po.getSuppliers()),
                split(po.getWarehouses()),
                split(po.getStatusText()),
                po.getQtyPlan(),
                po.getQtyReady(),
                po.getRisk(),
                po.getCreatedTime());
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }
}
