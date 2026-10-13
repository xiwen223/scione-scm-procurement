package com.scione.scm.bill.domain.posync.enums;

/** 固定业务编码；使用显式 code，保持现有接口及数据库取值。 */
public enum LingxingPurchaseOrderStatusEnum {
    VOIDED(-1, "作废"),
    PENDING_SUBMIT(3, "待提交"),
    APPROVED(1, "待下单（已审核）"),
    PENDING_SIGN(2, "待签约（待发货）"),
    COMPLETED(9, "完成"),
    PENDING_APPROVAL(121, "待审核"),
    REJECTED(122, "驳回");

    private final int code;
    private final String description;

    LingxingPurchaseOrderStatusEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() { return code; }

    public String getDescription() { return description; }

    public boolean matches(Integer value) { return value != null && value == code; }

}
