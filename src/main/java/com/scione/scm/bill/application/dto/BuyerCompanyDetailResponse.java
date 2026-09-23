package com.scione.scm.bill.application.dto;

import java.time.LocalDateTime;

public record BuyerCompanyDetailResponse(
        String id, String companyName, String companyShortName, String creditCode, String postCode, String fax,
        String legalPerson, String address, String phone, String bankName, String bankAccount, String maskedBankAccount,
        String sealName, String sealUrl, String sealBase64, String fadadaSealId, String openCorpId, Integer identStatus,
        Integer priority, boolean isDefault, boolean isActive, LocalDateTime createTime, LocalDateTime updateTime) {
}
