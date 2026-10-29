package com.scione.scm.bill.domain.goods.enums;

/** 根据修改前的货品状态确定外部写入操作，持久化稳定编码。 */
public enum GoodsSyncOperationEnum {
    CREATE(0), UPDATE(1);

    private final int code;

    /**
     * 初始化外部同步操作枚举的稳定业务编码及显示信息。
     *
     * @param code 稳定业务编码，不使用枚举序号
     */
    GoodsSyncOperationEnum(int code) { this.code = code; }

    /**
     * 取得外部同步操作的稳定业务编码。
     *
     * @return 外部同步操作业务编码
     */
    public int getCode() { return code; }
}
