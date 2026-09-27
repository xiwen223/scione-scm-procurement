package com.scione.scm.bill.application.dto;

/** 操作类型下拉选项：code 为落库值（ProcurementOperationType.code），name 为中文名。 */
public record ProcurementOperationTypeResponse(String code, String name) {
}
