package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

/** 修改子货品及比例；productId继承主货品，productType固定0，其余空字段保留已有值。 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CompanyGoodsChildModifyDTO extends CompanyGoodsSaveDTO {
    /** 兼容保留，不参与修改定位；按productId和goodsName定位。 */
    @Positive
    private Long id;
    @PositiveOrZero
    private Long version;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal quantity;
}
