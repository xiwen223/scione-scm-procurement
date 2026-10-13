package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ContractTemplateIdDTO(
        @NotNull @Min(1) Long id) {
}
