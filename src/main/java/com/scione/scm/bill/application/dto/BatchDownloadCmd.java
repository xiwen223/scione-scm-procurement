package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量下载已生成箱唛的请求。
 */
public record BatchDownloadCmd(
        @NotEmpty(message = "箱唛明细不能为空")
        List<@NotNull(message = "箱唛明细ID不能为空") Long> markIds) {
}
