package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

/** 仅维护已有独立子货品的关联。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CompanyGoodsRelationDTO {
    @NotBlank @Size(max = 128)
    private String sku;
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal quantity;
    @NotBlank @Size(max = 300)
    private String goodsName;
}
