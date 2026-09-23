package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;

public record ProcurementOperationLogPageRequest(
        String dataName,
        Integer businessType,
        String operationType,
        String operator,
        LocalDate startDate,
        LocalDate endDate,
        @Min(1) Integer pageNum,
        @Min(1) @Max(100) Integer pageSize) {
}
