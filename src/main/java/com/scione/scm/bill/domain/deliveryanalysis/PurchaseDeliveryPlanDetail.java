package com.scione.scm.bill.domain.deliveryanalysis;

import java.util.List;

public record PurchaseDeliveryPlanDetail(
        PurchaseDeliveryPlan plan,
        List<PurchaseDeliveryOrder> orders,
        List<PurchaseDeliveryEvent> timeline) {
}
