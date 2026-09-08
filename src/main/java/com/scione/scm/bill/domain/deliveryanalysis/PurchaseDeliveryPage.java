package com.scione.scm.bill.domain.deliveryanalysis;

import java.util.List;

public record PurchaseDeliveryPage(long total, List<PurchaseDeliveryPlan> records) {
}
