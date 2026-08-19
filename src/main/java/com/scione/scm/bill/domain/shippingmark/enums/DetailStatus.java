package com.scione.scm.bill.domain.shippingmark.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 箱唛明细状态。
 */
@Getter
@RequiredArgsConstructor
public enum DetailStatus {

    PENDING(1, "待处理"),
    PROCESSING(2, "处理中"),
    SUCCESS(3, "已生成"),
    FAILED(4, "已失败");

    private final int code;
    private final String desc;

    public static DetailStatus of(int code) {
        for (DetailStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return PENDING;
    }
}
