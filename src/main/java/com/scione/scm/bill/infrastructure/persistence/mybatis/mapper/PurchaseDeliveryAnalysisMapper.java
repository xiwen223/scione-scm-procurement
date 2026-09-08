package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryAnalysisQuery;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PurchaseDeliveryEventPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PurchaseDeliveryInboundPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PurchaseDeliveryOrderPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PurchaseDeliveryPlanPO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PurchaseDeliverySummaryPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 采购交付流程分析 Mapper。 */
public interface PurchaseDeliveryAnalysisMapper {

    long count(@Param("query") PurchaseDeliveryAnalysisQuery query);

    List<PurchaseDeliveryPlanPO> findPage(@Param("query") PurchaseDeliveryAnalysisQuery query);

    PurchaseDeliverySummaryPO summary(@Param("query") PurchaseDeliveryAnalysisQuery query);

    List<String> findDistinctBuyers(@Param("query") PurchaseDeliveryAnalysisQuery query);

    List<String> findDistinctSuppliers(@Param("query") PurchaseDeliveryAnalysisQuery query);

    List<String> findDistinctWarehouses(@Param("query") PurchaseDeliveryAnalysisQuery query);

    PurchaseDeliveryPlanPO findPlan(@Param("planSn") String planSn,
                                    @Param("query") PurchaseDeliveryAnalysisQuery query);

    List<PurchaseDeliveryOrderPO> findOrders(@Param("planSn") String planSn,
                                             @Param("query") PurchaseDeliveryAnalysisQuery query);

    List<com.scione.scm.bill.infrastructure.persistence.mybatis.po.PurchaseDeliveryOrderSkuLinePO> findOrderSkuLines(
            @Param("planSn") String planSn,
            @Param("query") PurchaseDeliveryAnalysisQuery query);

    PurchaseDeliveryOrderPO findOrder(@Param("orderSn") String orderSn,
                                      @Param("query") PurchaseDeliveryAnalysisQuery query);

    List<PurchaseDeliveryInboundPO> findInboundFacts(@Param("orderSn") String orderSn,
                                                      @Param("query") PurchaseDeliveryAnalysisQuery query);

    List<PurchaseDeliveryEventPO> findPlanTimeline(@Param("planSn") String planSn,
                                                    @Param("query") PurchaseDeliveryAnalysisQuery query);

    List<PurchaseDeliveryEventPO> findOrderTimeline(@Param("orderSn") String orderSn,
                                                     @Param("query") PurchaseDeliveryAnalysisQuery query);
}
