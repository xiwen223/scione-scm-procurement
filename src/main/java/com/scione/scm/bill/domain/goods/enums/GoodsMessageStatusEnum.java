package com.scione.scm.bill.domain.goods.enums;

/** 本地消息发送、消费共用的处理状态。 */
public enum GoodsMessageStatusEnum {
    PENDING(0), PROCESSING(1), SUCCEEDED(2), FAILED(3);

    private final int code;

    /**
     * 初始化同步消息处理状态枚举的稳定业务编码及显示信息。
     *
     * @param code 稳定业务编码，不使用枚举序号
     */
    GoodsMessageStatusEnum(int code) { this.code = code; }

    /**
     * 取得同步消息处理状态的稳定业务编码。
     *
     * @return 同步消息处理状态业务编码
     */
    public int getCode() { return code; }
}
