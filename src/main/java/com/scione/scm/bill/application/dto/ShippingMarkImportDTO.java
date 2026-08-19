package com.scione.scm.bill.application.dto;

/**
 * 导入任务创建结果。
 */
public record ShippingMarkImportDTO(Long id, String billNo, String billName, int detailCount) {
}
