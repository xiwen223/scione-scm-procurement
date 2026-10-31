package com.scione.scm.bill.domain.goods.enums;

public enum GoodsSyncTargetEnum {
    LINGXING(0), BLACKLAKE(1);

    private final int code;

    /**
     * 初始化外部同步目标系统枚举的稳定业务编码及显示信息。
     *
     * @param code 稳定业务编码，不使用枚举序号
     */
    GoodsSyncTargetEnum(int code) { this.code = code; }

    /**
     * 取得外部同步目标系统的稳定业务编码。
     *
     * @return 外部同步目标系统业务编码
     */
    public int getCode() { return code; }
}
