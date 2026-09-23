package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.Size;

public record BuyerCompanyUpsertRequest(
        @Size(max = 255) String companyName,
        @Size(max = 64) String companyShortName,
        @Size(max = 64) String creditCode,
        @Size(max = 128) String postCode,
        @Size(max = 128) String fax,
        @Size(max = 64) String legalPerson,
        @Size(max = 255) String address,
        @Size(max = 20) String phone,
        @Size(max = 128) String bankName,
        @Size(max = 64) String bankAccount,
        @Size(max = 512) String sealUrl,
        /** 印章名称：弹窗不维护签章，此处仅用于原样透传，避免全量更新清空列表页已设置的值 */
        @Size(max = 50) String sealName,
        String sealBase64,
        @Size(max = 128) String fadadaSealId,
        @Size(max = 128) String openCorpId,
        /** 法大大 /corp/get 返回的 identStatus 原值（如 identified / unidentified），用于计算库中 ident_status */
        @Size(max = 32) String identStatus,
        Integer priority,
        Boolean isDefault,
        Boolean isActive) {
}
