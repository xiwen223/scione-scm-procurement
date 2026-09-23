package com.scione.scm.bill.application.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合同批量下载请求
 */
public record ContractBatchDownloadRequest(
        @NotEmpty(message = "合同ID列表不能为空")
        @Size(max = 100, message = "单次最多下载100个合同")
        List<Long> contractIds
) {
}