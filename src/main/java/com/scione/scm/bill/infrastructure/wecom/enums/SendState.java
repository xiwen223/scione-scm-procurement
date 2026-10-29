package com.scione.scm.bill.infrastructure.wecom.enums;

/** 与原数据库状态兼容：0未发送、2成功、3失败、4未知；1仅表示正在执行。 */
public enum SendState {
    UNSENT(0), SUCCESS(2), FAILED(3), UNKNOWN(4);
    private final int databaseValue;
    SendState(int databaseValue) { this.databaseValue = databaseValue; }
    public int databaseValue() { return databaseValue; }
}
