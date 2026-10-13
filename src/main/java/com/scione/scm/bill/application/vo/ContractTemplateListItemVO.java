package com.scione.scm.bill.application.vo;

import java.time.LocalDateTime;

public record ContractTemplateListItemVO(
        String id, String templateName, Integer contractType, String contractTypeText,
        String objectKey, boolean isDefault, boolean isActive,
        LocalDateTime updateTime) {
}
