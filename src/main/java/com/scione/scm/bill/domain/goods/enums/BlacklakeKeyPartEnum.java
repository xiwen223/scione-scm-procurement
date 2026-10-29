package com.scione.scm.bill.domain.goods.enums;

/** 黑湖是否关键部位，使用固定业务编码。 */
public enum BlacklakeKeyPartEnum {
    NO(0, "否"),
    YES(1, "是");

    private final int code;
    private final String name;

    /**
     * 初始化黑湖关键部位枚举的稳定业务编码及显示信息。
     *
     * @param code 稳定业务编码，不使用枚举序号
     * @param name 业务显示名称
     */
    BlacklakeKeyPartEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * 取得黑湖关键部位的稳定业务编码。
     *
     * @return 黑湖关键部位业务编码
     */
    public int getCode() {
        return code;
    }

    /**
     * 取得黑湖关键部位的中文显示名称。
     *
     * @return 黑湖关键部位显示名称
     */
    public String getName() {
        return name;
    }
}
