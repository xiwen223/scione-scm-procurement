package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.vo.DeliveryAlertComponentVO;
import com.scione.scm.bill.application.vo.DeliveryAlertDetailVO;
import com.scione.scm.bill.application.vo.DeliveryAlertListItemVO;
import com.scione.scm.bill.application.vo.DeliveryAlertSummaryVO;
import com.scione.scm.bill.application.vo.DeliveryAlertSyncTimeVO;
import com.scione.scm.bill.application.vo.FilterOptionsVO;
import com.scione.scm.bill.config.DeliveryAlertProperties;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertPage;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertQuery;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertRepository;
import com.scione.scm.bill.domain.deliveryalert.DeliveryFollowUp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 采购交付预警看板查询应用服务。
 */
@Service
@RequiredArgsConstructor
public class DeliveryAlertAppService {

    private final DeliveryAlertRepository repository;
    private final DeliveryAlertProperties properties;

    public PageResult<DeliveryAlertListItemVO> findPage(DeliveryAlertQuery query) {
        query = withConfiguredStartDate(query);
        DeliveryAlertPage page = repository.findPage(query);
        var followUps = repository.findFollowUps(page.records().stream().map(r -> r.groupKey()).toList())
                .stream().collect(Collectors.groupingBy(DeliveryFollowUp::groupKey));
        return PageResult.of(query.pageNum(), query.pageSize(), page.total(),
                page.records().stream().map(record -> {
                    DeliveryAlertListItemVO dto = DeliveryAlertListItemVO.from(record);
                    dto.setFollowUps(followUps.getOrDefault(record.groupKey(), List.of()));
                    return dto;
                }).toList());
    }

    public DeliveryAlertSummaryVO summary(DeliveryAlertQuery query) {
        return DeliveryAlertSummaryVO.from(repository.summary(withConfiguredStartDate(query)));
    }

    public DeliveryAlertSyncTimeVO lastSyncTime() {
        return DeliveryAlertSyncTimeVO.from(repository.findLastOrderSyncTime());
    }

    public FilterOptionsVO filterOptions(DeliveryAlertQuery query) {
        query = withConfiguredStartDate(query);
        FilterOptionsVO dto = new FilterOptionsVO();
        dto.setSuppliers(repository.findDistinctSuppliers(query));
        dto.setBuyers(repository.findDistinctBuyers(query));
        dto.setWarehouses(repository.findDistinctWarehouses(query));
        dto.setFollowers(repository.findDistinctFollowers(query));
        return dto;
    }

    public List<DeliveryAlertComponentVO> findComponents(String groupKey) {
        var followUps = repository.findFollowUps(List.of(groupKey));
        return repository.findComponents(groupKey).stream()
                .map(record -> {
                    DeliveryAlertComponentVO dto = DeliveryAlertComponentVO.from(record);
                    dto.setFollowUps(followUps.stream()
                        .filter(row -> row.orderSn().equals(dto.getOrderSn()) && row.sku().equals(dto.getSku()))
                        .toList());
                    return dto;
                })
                .toList();
    }

    public DeliveryAlertDetailVO findDetail(String groupKey) {
        return DeliveryAlertDetailVO.from(repository.findDetail(groupKey));
    }

    public DeliveryAlertDetailVO findComponentDetail(String groupKey, Long componentId) {
        return DeliveryAlertDetailVO.from(repository.findComponentDetail(groupKey, componentId));
    }

    private DeliveryAlertQuery withConfiguredStartDate(DeliveryAlertQuery query) {
        return query.withStartDate(properties.getStartDate());
    }
}
