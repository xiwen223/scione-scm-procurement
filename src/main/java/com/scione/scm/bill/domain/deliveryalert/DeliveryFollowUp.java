package com.scione.scm.bill.domain.deliveryalert;

import java.util.Objects;
import java.time.LocalDate;

/** 对应现有reminder_excel_import表，采购单号加SKU唯一。 */
public record DeliveryFollowUp(
        String orderSn, String sku, String followerName, LocalDate expectedDeliveryDate,
        String reason, String remark, String groupKey) {

    public Key key() {
        return new Key(orderSn, sku);
    }

    public record Key(String orderSn, String sku) {
    }

    /** 同PO＋SKU的所有明细共用信息，分组位置不参与变更判断。 */
    public boolean sameInformation(DeliveryFollowUp other) {
        return other != null && key().equals(other.key())
                && Objects.equals(followerName, other.followerName)
                && Objects.equals(expectedDeliveryDate, other.expectedDeliveryDate)
                && Objects.equals(reason, other.reason)
                && Objects.equals(remark, other.remark);
    }
}
