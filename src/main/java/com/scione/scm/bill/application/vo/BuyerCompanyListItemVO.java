package com.scione.scm.bill.application.vo;

import java.time.LocalDateTime;

public record BuyerCompanyListItemVO(
        String id, String companyName, String companyShortName, String creditCode, String postCode, String fax,
        String address, String phone, String sealName, String sealUrl, String openCorpId, Integer identStatus,
        Integer priority, boolean isDefault, boolean isActive, LocalDateTime updateTime) {
}
