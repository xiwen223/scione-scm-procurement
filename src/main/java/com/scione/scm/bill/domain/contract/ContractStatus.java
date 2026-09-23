package com.scione.scm.bill.domain.contract;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ContractStatus {
    CREATED(1, "创建"),
    SIGNING(2, "签署中"),
    EXECUTING(3, "履行中"),
    COMPLETED(4, "完成"),
    CANCELLED(5, "取消");

    private final int code;
    private final String desc;

    public static ContractStatus of(int code) {
        for (ContractStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return CREATED;  // 默认返回"创建"
    }
}