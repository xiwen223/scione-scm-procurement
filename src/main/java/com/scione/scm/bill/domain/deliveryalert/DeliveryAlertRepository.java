package com.scione.scm.bill.domain.deliveryalert;

import java.util.List;

/**
 * 采购交付预警看板只读仓储。
 */
public interface DeliveryAlertRepository {

    DeliveryAlertPage findPage(DeliveryAlertQuery query);

    DeliveryAlertSummary summary(DeliveryAlertQuery query);

    List<String> findDistinctSuppliers(DeliveryAlertQuery query);

    List<String> findDistinctBuyers(DeliveryAlertQuery query);

    List<String> findDistinctWarehouses(DeliveryAlertQuery query);

    List<DeliveryAlertComponent> findComponents(String groupKey);

    DeliveryAlertDetail findDetail(String groupKey);

    DeliveryAlertDetail findComponentDetail(String groupKey, Long componentId);
}
