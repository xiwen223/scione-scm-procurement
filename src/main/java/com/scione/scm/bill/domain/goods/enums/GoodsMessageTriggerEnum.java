package com.scione.scm.bill.domain.goods.enums;

/** 本地消息触发来源，沿用表定义编码。 */
public enum GoodsMessageTriggerEnum {
    IMPORT_CREATE(0), GOODS_MODIFY(1);

    private final int code;

    /**
     * 初始化同步消息触发来源枚举的稳定业务编码及显示信息。
     *
     * @param code 稳定业务编码，不使用枚举序号
     */
    GoodsMessageTriggerEnum(int code) { this.code = code; }

    /**
     * 取得同步消息触发来源的稳定业务编码。
     *
     * @return 同步消息触发来源业务编码
     */
    public int getCode() { return code; }
}
