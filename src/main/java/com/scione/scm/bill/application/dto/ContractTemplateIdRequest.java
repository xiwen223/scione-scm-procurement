package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ContractTemplateIdRequest(
        @NotNull @Min(1) Long id) {
}
