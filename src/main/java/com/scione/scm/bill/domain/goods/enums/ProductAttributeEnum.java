package com.scione.scm.bill.domain.goods.enums;

/** 货品产品属性，使用固定业务编码。 */
public enum ProductAttributeEnum {
    SELF_MADE(0, "自制"),
    PURCHASED(1, "外购"),
    OUTSOURCED(2, "委外");

    private final int code;
    private final String name;

    ProductAttributeEnum(int code, String name) {
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
