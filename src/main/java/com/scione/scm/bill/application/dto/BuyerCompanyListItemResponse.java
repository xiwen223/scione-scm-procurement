package com.scione.scm.bill.application.dto;

import java.time.LocalDateTime;

public record BuyerCompanyListItemResponse(
        String id, String companyName, String companyShortName, String creditCode, String postCode, String fax,
        String address, String phone, String sealName, String sealUrl, String openCorpId, Integer identStatus,
        Integer priority, boolean isDefault, boolean isActive, LocalDateTime updateTime) {
}
