package com.scione.scm.bill.domain.company.enums;

/** 本地实名认证编码与法大大状态文本；保留原有未知值降级规则。 */
public enum CompanyIdentificationStatusEnum {
    UNIDENTIFIED(0, "unidentified"),
    IDENTIFIED(1, "identified");

    private final int code;
    private final String externalCode;

    CompanyIdentificationStatusEnum(int code, String externalCode) {
        this.code = code;
        this.externalCode = externalCode;
    }

    public int getCode() { return code; }

    public String getExternalCode() { return externalCode; }

    public static CompanyIdentificationStatusEnum fromCode(Integer code) {
        return code != null && code == IDENTIFIED.code ? IDENTIFIED : UNIDENTIFIED;
    }

    public static CompanyIdentificationStatusEnum fromExternalCode(String code) {
        return IDENTIFIED.externalCode.equals(code) ? IDENTIFIED : UNIDENTIFIED;
    }
}
