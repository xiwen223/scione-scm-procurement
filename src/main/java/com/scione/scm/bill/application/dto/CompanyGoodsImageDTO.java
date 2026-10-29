package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;
import lombok.Data;

/** 企业商品库图片信息，保存时按objectKey复制到公开桶并生成访问地址。 */
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
