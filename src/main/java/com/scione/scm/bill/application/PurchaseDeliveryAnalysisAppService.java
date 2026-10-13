package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.vo.PurchaseDeliveryFilterOptionsVO;
import com.scione.scm.bill.application.vo.PurchaseDeliveryOrderVO;
import com.scione.scm.bill.application.vo.PurchaseDeliveryOrderDetailVO;
import com.scione.scm.bill.application.vo.PurchaseDeliveryOrderSkuLineVO;
import com.scione.scm.bill.application.vo.PurchaseDeliveryPlanVO;
import com.scione.scm.bill.application.vo.PurchaseDeliveryPlanDetailVO;
import com.scione.scm.bill.application.vo.PurchaseDeliverySummaryVO;
import com.scione.scm.bill.config.PurchaseDeliveryAnalysisProperties;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryAnalysisQuery;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryAnalysisRepository;
import com.scione.scm.bill.domain.deliveryanalysis.PurchaseDeliveryPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 采购交付流程分析应用服务。 */
@Service
@RequiredArgsConstructor
public class PurchaseDeliveryAnalysisAppService {

    private final PurchaseDeliveryAnalysisRepository repository;
    private final PurchaseDeliveryAnalysisProperties properties;

    public PageResult<PurchaseDeliveryPlanVO> findPage(PurchaseDeliveryAnalysisQuery query) {
        PurchaseDeliveryAnalysisQuery scopedQuery = withConfiguredStartDate(query);
        PurchaseDeliveryPage page = repository.findPage(scopedQuery);
        return PageResult.of(scopedQuery.pageNum(), scopedQuery.pageSize(), page.total(),
                page.records().stream().map(PurchaseDeliveryPlanVO::from).toList());
    }

    public PurchaseDeliverySummaryVO summary(PurchaseDeliveryAnalysisQuery query) {
        return PurchaseDeliverySummaryVO.from(repository.summary(withConfiguredStartDate(query)));
    }

    public PurchaseDeliveryFilterOptionsVO filterOptions(PurchaseDeliveryAnalysisQuery query) {
        PurchaseDeliveryAnalysisQuery scopedQuery = withConfiguredStartDate(query);
        PurchaseDeliveryFilterOptionsVO dto = new PurchaseDeliveryFilterOptionsVO();
        dto.setBuyers(repository.findDistinctBuyers(scopedQuery));
        dto.setSuppliers(repository.findDistinctSuppliers(scopedQuery));
        dto.setWarehouses(repository.findDistinctWarehouses(scopedQuery));
        return dto;
    }

    public List<PurchaseDeliveryOrderVO> findOrders(String planSn, PurchaseDeliveryAnalysisQuery query) {
        return repository.findOrders(planSn, withConfiguredStartDate(query)).stream()
                .map(PurchaseDeliveryOrderVO::from)
                .toList();
    }

    public List<PurchaseDeliveryOrderSkuLineVO> findOrderSkuLines(
            String planSn, PurchaseDeliveryAnalysisQuery query) {
        return repository.findOrderSkuLines(planSn, withConfiguredStartDate(query)).stream()
                .map(PurchaseDeliveryOrderSkuLineVO::from)
                .toList();
    }

    public PurchaseDeliveryPlanDetailVO findPlanDetail(String planSn, PurchaseDeliveryAnalysisQuery query) {
        return PurchaseDeliveryPlanDetailVO.from(
                repository.findPlanDetail(planSn, withConfiguredStartDate(query)));
    }

    public PurchaseDeliveryOrderDetailVO findOrderDetail(String orderSn, String planSn, String sku,
                                                          PurchaseDeliveryAnalysisQuery query) {
        return PurchaseDeliveryOrderDetailVO.from(
                repository.findOrderDetail(orderSn, planSn, sku, withConfiguredStartDate(query)));
    }

    private PurchaseDeliveryAnalysisQuery withConfiguredStartDate(PurchaseDeliveryAnalysisQuery query) {
        return query.withConfiguredStartDate(properties.getStartDate());
    }
}
