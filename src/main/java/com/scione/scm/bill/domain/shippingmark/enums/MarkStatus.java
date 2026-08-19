package com.scione.scm.bill.domain.shippingmark.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 箱唛主单状态。
 */
@Getter
@RequiredArgsConstructor
public enum MarkStatus {

    PENDING(1, "待处理"),
    PROCESSING(2, "处理中"),
    DONE(3, "已处理");

    private final int code;
    private final String desc;

    public static MarkStatus of(int code) {
        for (MarkStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return PENDING;
    }
}
