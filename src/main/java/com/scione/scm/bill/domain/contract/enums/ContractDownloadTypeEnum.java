package com.scione.scm.bill.domain.contract.enums;

/** 固定业务编码；使用显式 code，保持现有接口及数据库取值。 */
public enum ContractDownloadTypeEnum {
    ORIGINAL("original", "原始合同"),
    SIGNED("signed", "已签署合同");

    private final String code;
    private final String description;

    ContractDownloadTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() { return code; }

    public String getDescription() { return description; }

    public boolean matches(String value) { return code.equals(value); }

}
