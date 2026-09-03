package com.scione.scm.bill.application;

import com.scione.common.model.PageResult;
import com.scione.scm.bill.application.dto.DeliveryAlertComponentDTO;
import com.scione.scm.bill.application.dto.DeliveryAlertDetailDTO;
import com.scione.scm.bill.application.dto.DeliveryAlertListItemDTO;
import com.scione.scm.bill.application.dto.DeliveryAlertSummaryDTO;
import com.scione.scm.bill.application.dto.FilterOptionsDTO;
import com.scione.scm.bill.config.DeliveryAlertProperties;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertPage;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertQuery;
import com.scione.scm.bill.domain.deliveryalert.DeliveryAlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 采购交付预警看板查询应用服务。
 */
@Service
@RequiredArgsConstructor
public class DeliveryAlertAppService {

    private final DeliveryAlertRepository repository;
    private final DeliveryAlertProperties properties;

    public PageResult<DeliveryAlertListItemDTO> findPage(DeliveryAlertQuery query) {
        query = withConfiguredStartDate(query);
        DeliveryAlertPage page = repository.findPage(query);
        return PageResult.of(query.pageNum(), query.pageSize(), page.total(),
                page.records().stream().map(DeliveryAlertListItemDTO::from).toList());
    }

    public DeliveryAlertSummaryDTO summary(DeliveryAlertQuery query) {
        return DeliveryAlertSummaryDTO.from(repository.summary(withConfiguredStartDate(query)));
    }

    public FilterOptionsDTO filterOptions(DeliveryAlertQuery query) {
        query = withConfiguredStartDate(query);
        FilterOptionsDTO dto = new FilterOptionsDTO();
        dto.setSuppliers(repository.findDistinctSuppliers(query));
        dto.setBuyers(repository.findDistinctBuyers(query));
        dto.setWarehouses(repository.findDistinctWarehouses(query));
        return dto;
    }

    public List<DeliveryAlertComponentDTO> findComponents(String groupKey) {
        return repository.findComponents(groupKey).stream()
                .map(DeliveryAlertComponentDTO::from)
                .toList();
    }

    public DeliveryAlertDetailDTO findDetail(String groupKey) {
        return DeliveryAlertDetailDTO.from(repository.findDetail(groupKey));
    }

    public DeliveryAlertDetailDTO findComponentDetail(String groupKey, Long componentId) {
        return DeliveryAlertDetailDTO.from(repository.findComponentDetail(groupKey, componentId));
    }

    private DeliveryAlertQuery withConfiguredStartDate(DeliveryAlertQuery query) {
        return query.withStartDate(properties.getStartDate());
    }
}
