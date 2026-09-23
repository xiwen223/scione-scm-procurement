package com.scione.scm.bill.domain.contract.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 合同类型，与 contract.contract_type、contract_template.contract_type 取值保持一致。
 */
@Getter
@RequiredArgsConstructor
public enum ContractType {

    PURCHASE(1, "采购合同"),
    SALES(2, "购销合同"),
    FRAMEWORK(3, "框架合同"),
    EMPLOYMENT(4, "人事合同");

    private final int code;
    private final String desc;

    public static ContractType of(int code) {
        for (ContractType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }

    public static boolean supports(Integer code) {
        return code != null && of(code) != null;
    }

    public static String descOf(Integer code) {
        ContractType type = code == null ? null : of(code);
        return type == null ? null : type.desc;
    }
}
