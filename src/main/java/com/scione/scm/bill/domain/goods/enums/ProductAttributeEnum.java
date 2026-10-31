package com.scione.scm.bill.domain.goods.enums;

/** 货品产品属性，使用固定业务编码。 */
public enum ProductAttributeEnum {
    SELF_MADE(0, "自制"),
    PURCHASED(1, "外购"),
    OUTSOURCED(2, "委外");

    private final int code;
    private final String name;

    /**
     * 初始化产品属性枚举的稳定业务编码及显示信息。
     *
     * @param code 稳定业务编码，不使用枚举序号
     * @param name 业务显示名称
     */
    ProductAttributeEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * 取得产品属性的稳定业务编码。
     *
     * @return 产品属性业务编码
     */
    public int getCode() {
        return code;
    }

    /**
     * 取得产品属性的中文显示名称。
     *
     * @return 产品属性显示名称
     */
    public String getName() {
        return name;
    }
}
