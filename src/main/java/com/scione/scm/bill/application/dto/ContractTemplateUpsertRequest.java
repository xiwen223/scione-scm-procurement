package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.Size;

public record ContractTemplateUpsertRequest(
        @Size(max = 255) String templateName,
        Integer contractType,
        @Size(max = 512) String objectKey,
        Boolean isDefault,
        Boolean isActive) {
}
