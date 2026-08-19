package com.scione.scm.bill.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 创建箱唛单命令。
 */
@Data
public class CreateShippingMarkCmd {

    @NotBlank(message = "单据编号不能为空")
    private String billNo;

    @NotBlank(message = "单据名称不能为空")
    private String billName;

    @NotNull(message = "创建人ID不能为空")
    private String createdBy;

    @NotBlank(message = "创建人不能为空")
    private String creator;

    @Valid
    @NotEmpty(message = "明细不能为空")
    private List<DetailItem> details;

    @Data
    public static class DetailItem {

        private String purchaseOrderNo;

        @NotBlank(message = "商品编码不能为空")
        private String skuCode;

        private String skuName;

        private String skuImage;
    }
}
