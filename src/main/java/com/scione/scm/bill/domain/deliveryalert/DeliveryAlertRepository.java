package com.scione.scm.bill.domain.deliveryalert;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购交付预警看板仓储，人工信息独立维护，不修改同步来源表。
 */
public interface DeliveryAlertRepository {

    DeliveryAlertPage findPage(DeliveryAlertQuery query);

    DeliveryAlertSummary summary(DeliveryAlertQuery query);

    LocalDateTime findLastOrderSyncTime();

    List<String> findDistinctSuppliers(DeliveryAlertQuery query);

    List<String> findDistinctBuyers(DeliveryAlertQuery query);

    List<String> findDistinctWarehouses(DeliveryAlertQuery query);

    List<String> findDistinctFollowers(DeliveryAlertQuery query);

    List<DeliveryAlertComponent> findComponents(String groupKey);

    DeliveryAlertDetail findDetail(String groupKey);

    DeliveryAlertDetail findComponentDetail(String groupKey, Long componentId);

    /** 只按看板中的采购单与真实采购SKU匹配，不依赖采购计划。 */
    List<DeliveryFollowUp.Key> findFollowUpKeys(List<DeliveryFollowUp.Key> keys, LocalDate startDate);

    DeliveryFollowUp lockFollowUp(DeliveryFollowUp.Key key);

    void saveFollowUp(DeliveryFollowUp row, String operator);

    List<DeliveryFollowUp> findFollowUps(List<String> groupKeys);
}
