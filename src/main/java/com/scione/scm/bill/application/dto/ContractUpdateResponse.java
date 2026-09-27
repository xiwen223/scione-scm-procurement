package com.scione.scm.bill.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 合同修改响应。
 */
@Data
@AllArgsConstructor
@Schema(description = "合同修改响应")
public class ContractUpdateResponse {

    @Schema(description = "合同ID", example = "123")
    private Long contractId;

    @Schema(description = "合同编号", example = "HT202609231234")
    private String contractNo;

    @Schema(description = "合同文件URL（修改后重新生成的文件）", example = "https://s3.amazonaws.com/...")
    private String contractPdfUrl;

    @Schema(description = "响应消息", example = "合同修改成功")
    private String message;
}
