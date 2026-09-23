package com.scione.scm.bill.domain.procurementlog.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/** procurement_operation_log.business_type 的业务类型枚举。 */
@Getter
@RequiredArgsConstructor
public enum ProcurementBusinessType {

    CONTRACT(1, "合同"),
    BUYER_COMPANY(2, "公司信息"),
    CONTRACT_TEMPLATE(3, "合同模板");

    private final int code;
    private final String name;

    public static Optional<ProcurementBusinessType> findByCode(Integer code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.code == code)
                .findFirst();
    }
}
