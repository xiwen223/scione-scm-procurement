package com.scione.scm.bill.domain.deliveryanalysis;

import java.util.List;

public record PurchaseDeliveryOrderDetail(
        PurchaseDeliveryOrder order,
        List<PurchaseDeliveryInbound> inbounds,
        List<PurchaseDeliveryEvent> timeline) {
}
