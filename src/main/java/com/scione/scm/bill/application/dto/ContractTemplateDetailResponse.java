package com.scione.scm.bill.application.dto;

import java.time.LocalDateTime;

public record ContractTemplateDetailResponse(
        String id, String templateName, Integer contractType, String contractTypeText,
        String objectKey, boolean isDefault, boolean isActive,
        LocalDateTime createTime, LocalDateTime updateTime) {
}
