package com.scione.scm.bill.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手动创建合同响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "手动创建合同响应")
public class ContractCreateResponse {

    @Schema(description = "合同ID")
    private Long contractId;

    @Schema(description = "合同编号")
    private String contractNo;

    @Schema(description = "采购单号")
    private String purchaseOrderNo;

    @Schema(description = "合同文件URL（Excel）")
    private String contractFileUrl;

    @Schema(description = "响应消息")
    private String message;
}