package com.scione.scm.bill.domain.goods.enums;

/** 领星默认质检方式，使用固定业务编码。 */
public enum LingxingQualityInspectionMethodEnum {
    SAMPLING(1, "抽检"),
    FULL(2, "全检"),
    EXEMPT(3, "免检");

    private final int code;
    private final String name;

    /**
     * 初始化领星默认质检方式枚举的稳定业务编码及显示信息。
     *
     * @param code 稳定业务编码，不使用枚举序号
     * @param name 业务显示名称
     */
    LingxingQualityInspectionMethodEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * 取得领星默认质检方式的稳定业务编码。
     *
     * @return 领星默认质检方式业务编码
     */
    public int getCode() {
        return code;
    }

    /**
     * 取得领星默认质检方式的中文显示名称。
     *
     * @return 领星默认质检方式显示名称
     */
    public String getName() {
        return name;
    }
}
