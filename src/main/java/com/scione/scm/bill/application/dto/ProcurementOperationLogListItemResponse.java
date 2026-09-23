package com.scione.scm.bill.application.dto;

import java.time.LocalDateTime;

/** 日志列表响应不暴露 dataId，但保留表中其他可检索和展示字段。 */
public record ProcurementOperationLogListItemResponse(
        Long id,
        Integer businessType,
        String businessTypeText,
        String dataName,
        Long operatorId,
        String operatorName,
        String operationType,
        String operationDesc,
        String operationDetails,
        String ipAddress,
        LocalDateTime createTime) {
}
