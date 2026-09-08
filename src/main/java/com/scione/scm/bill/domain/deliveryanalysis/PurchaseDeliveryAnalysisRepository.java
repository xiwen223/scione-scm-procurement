package com.scione.scm.bill.domain.deliveryanalysis;

import java.util.List;

/** 采购交付流程分析只读领域仓储。 */
public interface PurchaseDeliveryAnalysisRepository {

    PurchaseDeliveryPage findPage(PurchaseDeliveryAnalysisQuery query);

    PurchaseDeliverySummary summary(PurchaseDeliveryAnalysisQuery query);

    List<String> findDistinctBuyers(PurchaseDeliveryAnalysisQuery query);

    List<String> findDistinctSuppliers(PurchaseDeliveryAnalysisQuery query);

    List<String> findDistinctWarehouses(PurchaseDeliveryAnalysisQuery query);

    List<PurchaseDeliveryOrder> findOrders(String planSn, PurchaseDeliveryAnalysisQuery query);

    List<PurchaseDeliveryOrderSkuLine> findOrderSkuLines(String planSn, PurchaseDeliveryAnalysisQuery query);

    PurchaseDeliveryPlanDetail findPlanDetail(String planSn, PurchaseDeliveryAnalysisQuery query);

    PurchaseDeliveryOrderDetail findOrderDetail(String orderSn, PurchaseDeliveryAnalysisQuery query);
}
