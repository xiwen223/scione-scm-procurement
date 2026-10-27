package com.scione.scm.bill.domain.goods.enums;

/** 本地消息触发来源，沿用表定义编码。 */
public enum GoodsMessageTriggerEnum {
    IMPORT_CREATE(0), GOODS_MODIFY(1);

    private final int code;

    GoodsMessageTriggerEnum(int code) { this.code = code; }

    public int getCode() { return code; }
}
