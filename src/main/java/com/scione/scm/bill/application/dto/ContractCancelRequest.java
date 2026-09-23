package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.Size;

/** 作废合同请求。 */
public record ContractCancelRequest(
        @Size(max = 500, message = "作废原因不能超过500个字符") String reason) {
}
