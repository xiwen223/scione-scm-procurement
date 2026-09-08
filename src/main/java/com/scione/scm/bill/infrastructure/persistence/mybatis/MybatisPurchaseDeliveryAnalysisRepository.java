package com.scione.scm.bill.infrastructure.persistence.mybatis;

import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryAnalysisQuery;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryAnalysisRepository;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryEvent;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryInbound;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryOrder;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryOrderDetail;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryOrderSkuLine;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryPage;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryPlan;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryPlanDetail;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliverySummary;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.PurchaseDeliveryAnalysisMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PurchaseDeliveryEventPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PurchaseDeliveryInboundPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PurchaseDeliveryOrderPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PurchaseDeliveryOrderSkuLinePO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PurchaseDeliveryPlanPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PurchaseDeliverySummaryPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** 基于 MyBatis 的采购交付流程分析只读仓储。 */
@Repository
@RequiredArgsConstructor
public class MybatisPurchaseDeliveryAnalysisRepository implements PurchaseDeliveryAnalysisRepository {

    private static final String LIST_SEPARATOR = "||";
    private final PurchaseDeliveryAnalysisMapper mapper;

    @Override
    public PurchaseDeliveryPage findPage(PurchaseDeliveryAnalysisQuery query) {
        long total = mapper.count(query);
        List<PurchaseDeliveryPlan> records = mapper.findPage(query).stream().map(this::toPlan).toList();
        return new PurchaseDeliveryPage(total, records);
    }

    @Override
    public PurchaseDeliverySummary summary(PurchaseDeliveryAnalysisQuery query) {
        PurchaseDeliverySummaryPO po = mapper.summary(query);
        return new PurchaseDeliverySummary(query.asOf(), po.getSyncStatus(), po.getDataUpdatedAt(),
                po.getTotal(), po.getPpCreateCount(),
                po.getPpApprovalCount(), po.getPoCreateCount(), po.getPoApprovalCount(),
                po.getSupplierSentCount(), po.getFinancePaymentCount(), po.getReceiptCount(),
                po.getInboundCreateCount(), po.getCompletedCount(), po.getOrderOverdueCount(),
                po.getDeliveryOverdueCount());
    }

    @Override
    public List<String> findDistinctBuyers(PurchaseDeliveryAnalysisQuery query) {
        return mapper.findDistinctBuyers(query);
    }

    @Override
    public List<String> findDistinctSuppliers(PurchaseDeliveryAnalysisQuery query) {
        return mapper.findDistinctSuppliers(query);
    }

    @Override
    public List<String> findDistinctWarehouses(PurchaseDeliveryAnalysisQuery query) {
        return mapper.findDistinctWarehouses(query);
    }

    @Override
    public List<PurchaseDeliveryOrder> findOrders(String planSn, PurchaseDeliveryAnalysisQuery query) {
        requirePlan(planSn, query);
        return mapper.findOrders(planSn, query).stream().map(this::toOrder).toList();
    }

    @Override
    public List<PurchaseDeliveryOrderSkuLine> findOrderSkuLines(
            String planSn, PurchaseDeliveryAnalysisQuery query) {
        requirePlan(planSn, query);
        return mapper.findOrderSkuLines(planSn, query).stream().map(this::toOrderSkuLine).toList();
    }

    @Override
    public PurchaseDeliveryPlanDetail findPlanDetail(String planSn, PurchaseDeliveryAnalysisQuery query) {
        PurchaseDeliveryPlan plan = toPlan(requirePlan(planSn, query));
        List<PurchaseDeliveryOrder> orders = mapper.findOrders(planSn, query).stream()
                .map(this::toOrder).toList();
        List<PurchaseDeliveryEvent> timeline = mapper.findPlanTimeline(planSn, query).stream()
                .map(this::toEvent).toList();
        return new PurchaseDeliveryPlanDetail(plan, orders, timeline);
    }

    @Override
    public PurchaseDeliveryOrderDetail findOrderDetail(String orderSn, PurchaseDeliveryAnalysisQuery query) {
        PurchaseDeliveryOrderPO po = mapper.findOrder(orderSn, query);
        if (po == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND);
        }
        List<PurchaseDeliveryInbound> inbounds = mapper.findInboundFacts(orderSn, query).stream()
                .map(this::toInbound).toList();
        List<PurchaseDeliveryEvent> timeline = mapper.findOrderTimeline(orderSn, query).stream()
                .map(this::toEvent).toList();
        return new PurchaseDeliveryOrderDetail(toOrder(po), inbounds, timeline);
    }

    private PurchaseDeliveryPlanPO requirePlan(String planSn, PurchaseDeliveryAnalysisQuery query) {
        PurchaseDeliveryPlanPO po = mapper.findPlan(planSn, query);
        if (po == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND);
        }
        return po;
    }

    private PurchaseDeliveryPlan toPlan(PurchaseDeliveryPlanPO po) {
        return new PurchaseDeliveryPlan(po.getPlanSn(), po.getProductName(), po.getSku(), po.getStatusText(),
                po.getApprovalStatus(), po.getCreatedAt(), po.getApprovalAt(), po.getPlanQty(), po.getPurchaseQty(),
                po.getRelatedOrderCount(), split(po.getOrderSns()), split(po.getBuyers()),
                split(po.getSuppliers()), split(po.getWarehouses()), po.getLastPoCreatedAt(),
                po.getOrderLeadHours(), po.getOrderTimeliness(), Boolean.TRUE.equals(po.getOrderCoverageConfirmed()),
                po.getCurrentNode(), po.getMaxDeliveryHours(), po.getDeliveryStatus(),
                Boolean.TRUE.equals(po.getAllocationAvailable()), po.getInboundQty(), po.getAchievementRate());
    }

    private PurchaseDeliveryOrder toOrder(PurchaseDeliveryOrderPO po) {
        return new PurchaseDeliveryOrder(po.getOrderId(), po.getOrderSn(), po.getPlanSn(), po.getSkus(),
                po.getProductNames(), po.getStatusText(), po.getSupplier(), po.getWarehouse(), po.getBuyer(),
                po.getSettlementMethod(), po.getSettlementType(), po.getSourceType(), po.getCreatedAt(),
                po.getApprovalAt(), po.getSentAt(), po.getPurchaseQty(), split(po.getReceiptSns()),
                po.getReceiptCount(), split(po.getInboundSns()), po.getInboundCount(), po.getValidInboundQty(),
                po.getCompletionInboundSn(), po.getCompletionAt(), po.getDeliveryHours(), po.getDeliveryStatus(),
                po.getCurrentNode(), Boolean.TRUE.equals(po.getAllocationAvailable()), po.getAllocatedQty());
    }

    private PurchaseDeliveryOrderSkuLine toOrderSkuLine(PurchaseDeliveryOrderSkuLinePO po) {
        return new PurchaseDeliveryOrderSkuLine(po.getRowKey(), po.getOrderId(), po.getOrderSn(), po.getPlanSn(),
                po.getSku(), po.getProductName(), po.getStatusText(), po.getSupplier(), po.getWarehouse(),
                po.getBuyer(), po.getSettlementMethod(), po.getSettlementType(), po.getSourceType(),
                po.getCreatedAt(), po.getApprovalAt(), po.getSentAt(), po.getPurchaseQty(),
                split(po.getReceiptSns()), po.getReceiptCount(), po.getReceiptQty(), split(po.getInboundSns()),
                po.getInboundCount(), po.getValidInboundQty(), po.getPoCompletionInboundSn(),
                po.getPoCompletionAt(), po.getPoDeliveryHours(), po.getPoDeliveryStatus(), po.getPoCurrentNode(),
                Boolean.TRUE.equals(po.getAllocationAvailable()), po.getAllocatedQty());
    }

    private PurchaseDeliveryInbound toInbound(PurchaseDeliveryInboundPO po) {
        return new PurchaseDeliveryInbound(po.getInboundId(), po.getInboundSn(), po.getCreatedAt(),
                po.getInboundQty(), po.getCumulativeQty(), Boolean.TRUE.equals(po.getCompletionInbound()));
    }

    private PurchaseDeliveryEvent toEvent(PurchaseDeliveryEventPO po) {
        return new PurchaseDeliveryEvent(po.getEventId(), po.getSourceType(), po.getSourceSn(), po.getEventTime(),
                po.getTitle(), po.getDescription(), po.getOperator(), po.getQuantity(), po.getCumulativeQuantity());
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(Pattern.quote(LIST_SEPARATOR)))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }
}
