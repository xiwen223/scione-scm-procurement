package com.scione.scm.bill.domain.goods.enums;

/** 根据修改前的货品状态确定外部写入操作，持久化稳定编码。 */
public enum GoodsSyncOperationEnum {
    CREATE(0), UPDATE(1);

    private final int code;

    GoodsSyncOperationEnum(int code) { this.code = code; }

    public int getCode() { return code; }
}
