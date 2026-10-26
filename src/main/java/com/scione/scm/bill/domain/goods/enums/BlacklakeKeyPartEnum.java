package com.scione.scm.bill.domain.goods.enums;

/** 黑湖是否关键部位，使用固定业务编码。 */
public enum BlacklakeKeyPartEnum {
    NO(0, "否"),
    YES(1, "是");

    private final int code;
    private final String name;

    BlacklakeKeyPartEnum(int code, String name) {
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
