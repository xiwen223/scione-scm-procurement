package com.scione.scm.bill.application.vo;

/** 行校验失败允许其他有效行提交；数据库发布失败时有效行全部回滚。 */
public record DeliveryFollowUpImportVO(
        int total, int inserted, int updated, int unchanged, int failed,
        Long logId, String errorFileUrl, String message) {
}
