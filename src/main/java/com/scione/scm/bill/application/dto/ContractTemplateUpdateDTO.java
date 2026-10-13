package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ContractTemplateUpdateDTO(
        @NotNull @Min(1) Long id,
        @Size(max = 255) String templateName,
        Integer contractType,
        @Size(max = 512) String objectKey,
        Boolean isDefault,
        Boolean isActive) {

    public ContractTemplateUpsertDTO toUpsertRequest() {
        return new ContractTemplateUpsertDTO(
                templateName,
                contractType,
                objectKey,
                isDefault,
                isActive);
    }
}
