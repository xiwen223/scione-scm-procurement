package com.scione.scm.bill.domain.contract.enums;

/** 固定业务编码；使用显式 code，保持现有接口及数据库取值。 */
public enum ContractCreateTypeEnum {
    AUTO(1, "自动创建"),
    MANUAL(2, "手动创建");

    private final int code;
    private final String description;

    ContractCreateTypeEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() { return code; }

    public String getDescription() { return description; }

    public boolean matches(Integer value) { return value != null && value == code; }

}
