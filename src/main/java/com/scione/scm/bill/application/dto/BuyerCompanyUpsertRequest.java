package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.Size;

public record BuyerCompanyUpsertRequest(
        @Size(max = 255) String companyName,
        @Size(max = 64) String companyShortName,
        @Size(max = 64) String creditCode,
        @Size(max = 64) String legalPerson,
        @Size(max = 255) String address,
        @Size(max = 20) String phone,
        @Size(max = 128) String bankName,
        @Size(max = 64) String bankAccount,
        @Size(max = 512) String sealUrl,
        String sealBase64,
        @Size(max = 128) String fadadaSealId,
        @Size(max = 128) String openCorpId,
        Integer priority,
        Boolean isDefault,
        Boolean isActive) {
}
