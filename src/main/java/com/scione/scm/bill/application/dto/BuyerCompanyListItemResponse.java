package com.scione.scm.bill.application.dto;

import java.time.LocalDateTime;

public record BuyerCompanyListItemResponse(
        String id, String companyName, String companyShortName, String creditCode, String address,
        String phone, String sealUrl, String openCorpId, Integer priority, boolean isDefault,
        boolean isActive, LocalDateTime updateTime) {
}
