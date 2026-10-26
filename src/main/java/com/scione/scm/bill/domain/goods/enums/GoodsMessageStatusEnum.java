package com.scione.scm.bill.domain.goods.enums;

/** 本地消息发送、消费共用的处理状态。 */
public enum GoodsMessageStatusEnum {
    PENDING(0), PROCESSING(1), SUCCEEDED(2), FAILED(3);

    private final int code;

    GoodsMessageStatusEnum(int code) { this.code = code; }

    public int getCode() { return code; }
}
