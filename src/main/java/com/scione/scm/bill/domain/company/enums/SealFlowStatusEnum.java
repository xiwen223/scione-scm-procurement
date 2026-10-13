package com.scione.scm.bill.domain.company.enums;

/** 固定业务编码；使用显式 code，保持现有接口及数据库取值。 */
public enum SealFlowStatusEnum {
    AUDITING(0, "审核中"),
    APPROVED(1, "审核成功"),
    REJECTED(2, "审核失败");

    private final int code;
    private final String description;

    SealFlowStatusEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() { return code; }

    public String getDescription() { return description; }

    public boolean matches(Integer value) { return value != null && value == code; }

}
