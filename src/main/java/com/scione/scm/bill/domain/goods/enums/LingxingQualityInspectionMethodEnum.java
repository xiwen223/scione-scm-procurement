package com.scione.scm.bill.domain.goods.enums;

/** 领星默认质检方式，使用固定业务编码。 */
public enum LingxingQualityInspectionMethodEnum {
    SAMPLING(1, "抽检"),
    FULL(2, "全检"),
    EXEMPT(3, "免检");

    private final int code;
    private final String name;

    LingxingQualityInspectionMethodEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
