package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;
import lombok.Data;

/** 企业商品库已上传图片信息，此接口不上传或删除 S3 文件。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CompanyGoodsImageDTO {
    @Size(max = 1024)
    private String objectKey;
    @Size(max = 2048)
    private String picUrl;
    @NotNull @Min(0) @Max(1)
    private Integer isPrimary;
}
