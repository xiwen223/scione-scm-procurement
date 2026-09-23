package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ContractTemplatePageRequest(
        String keyword,
        Integer contractType,
        Boolean isActive,
        Boolean defaultOnly,
        @Min(1) Integer pageNum,
        @Min(1) @Max(100) Integer pageSize) {
}
