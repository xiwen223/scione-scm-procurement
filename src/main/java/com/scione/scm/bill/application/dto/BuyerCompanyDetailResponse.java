package com.scione.scm.bill.application.dto;

import java.time.LocalDateTime;

public record BuyerCompanyDetailResponse(
        String id, String companyName, String companyShortName, String creditCode, String postCode, String fax,
        String legalPerson, String address, String phone, String bankName, String bankAccount, String maskedBankAccount,
        String sealName, String sealUrl, String sealBase64, String fadadaSealId,
        /** 印章审核状态：0-审核中，1-审核成功，2-审核失败；为 null 表示无印章或当前无审核状态 */
        Integer sealFlowStatus,
        /** 印章审核不通过的原因，仅审核失败时有值；为空表示没有失败原因，前端不展示 */
        String sealFailedReason,
        String openCorpId, Integer identStatus,
        Integer priority, boolean isDefault, boolean isActive, LocalDateTime createTime, LocalDateTime updateTime) {
}
