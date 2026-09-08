package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.dto.PurchaseDeliveryFilterOptionsDTO;
import com.scione.scm.bill.application.dto.PurchaseDeliveryOrderDTO;
import com.scione.scm.bill.application.dto.PurchaseDeliveryOrderDetailDTO;
import com.scione.scm.bill.application.dto.PurchaseDeliveryOrderSkuLineDTO;
import com.scione.scm.bill.application.dto.PurchaseDeliveryPlanDTO;
import com.scione.scm.bill.application.dto.PurchaseDeliveryPlanDetailDTO;
import com.scione.scm.bill.application.dto.PurchaseDeliverySummaryDTO;
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

    public PageResult<PurchaseDeliveryPlanDTO> findPage(PurchaseDeliveryAnalysisQuery query) {
        PurchaseDeliveryAnalysisQuery scopedQuery = withConfiguredStartDate(query);
        PurchaseDeliveryPage page = repository.findPage(scopedQuery);
        return PageResult.of(scopedQuery.pageNum(), scopedQuery.pageSize(), page.total(),
                page.records().stream().map(PurchaseDeliveryPlanDTO::from).toList());
    }

    public PurchaseDeliverySummaryDTO summary(PurchaseDeliveryAnalysisQuery query) {
        return PurchaseDeliverySummaryDTO.from(repository.summary(withConfiguredStartDate(query)));
    }

    public PurchaseDeliveryFilterOptionsDTO filterOptions(PurchaseDeliveryAnalysisQuery query) {
        PurchaseDeliveryAnalysisQuery scopedQuery = withConfiguredStartDate(query);
        PurchaseDeliveryFilterOptionsDTO dto = new PurchaseDeliveryFilterOptionsDTO();
        dto.setBuyers(repository.findDistinctBuyers(scopedQuery));
        dto.setSuppliers(repository.findDistinctSuppliers(scopedQuery));
        dto.setWarehouses(repository.findDistinctWarehouses(scopedQuery));
        return dto;
    }

    public List<PurchaseDeliveryOrderDTO> findOrders(String planSn, PurchaseDeliveryAnalysisQuery query) {
        return repository.findOrders(planSn, withConfiguredStartDate(query)).stream()
                .map(PurchaseDeliveryOrderDTO::from)
                .toList();
    }

    public List<PurchaseDeliveryOrderSkuLineDTO> findOrderSkuLines(
            String planSn, PurchaseDeliveryAnalysisQuery query) {
        return repository.findOrderSkuLines(planSn, withConfiguredStartDate(query)).stream()
                .map(PurchaseDeliveryOrderSkuLineDTO::from)
                .toList();
    }

    public PurchaseDeliveryPlanDetailDTO findPlanDetail(String planSn, PurchaseDeliveryAnalysisQuery query) {
        return PurchaseDeliveryPlanDetailDTO.from(
                repository.findPlanDetail(planSn, withConfiguredStartDate(query)));
    }

    public PurchaseDeliveryOrderDetailDTO findOrderDetail(String orderSn, PurchaseDeliveryAnalysisQuery query) {
        return PurchaseDeliveryOrderDetailDTO.from(
                repository.findOrderDetail(orderSn, withConfiguredStartDate(query)));
    }

    private PurchaseDeliveryAnalysisQuery withConfiguredStartDate(PurchaseDeliveryAnalysisQuery query) {
        return query.withConfiguredStartDate(properties.getStartDate());
    }
}
