package com.scione.scm.bill.domain.goods.enums;

public enum GoodsSyncTargetEnum {
    LINGXING(0), BLACKLAKE(1);

    private final int code;

    GoodsSyncTargetEnum(int code) { this.code = code; }

    public int getCode() { return code; }
}
