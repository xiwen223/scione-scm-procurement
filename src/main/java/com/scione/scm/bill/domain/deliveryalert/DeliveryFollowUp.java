package com.scione.scm.bill.domain.deliveryalert;

import java.util.Objects;
import java.time.LocalDate;

/**
 * 对应现有reminder_excel_import表，采购单号加SKU唯一。
 * orderSn采购单号、sku SKU、followerName跟单员姓名、expectedDeliveryDate预计交期、
 * reason原因、remark备注、groupKey分组键（导入时生成）。
 */
public record DeliveryFollowUp(
        String orderSn, String sku, String followerName, LocalDate expectedDeliveryDate,
        String reason, String remark, String groupKey) {

    /** 取该跟单记录的定位键（采购单号+SKU），用于去重和关联。 */
    public Key key() {
        return new Key(orderSn, sku);
    }

    /** 唯一键：采购单号 + SKU。 */
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
