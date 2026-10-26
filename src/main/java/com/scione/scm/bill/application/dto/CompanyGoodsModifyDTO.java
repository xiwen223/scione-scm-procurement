package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 企业商品库主、子完整货品修改参数；空字段保留原值，未传版本时使用数据库当前版本。 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CompanyGoodsModifyDTO extends CompanyGoodsSaveDTO {
    @Valid
    private List<@NotNull @Valid CompanyGoodsChildModifyDTO> groupList;
    @Positive
    private Long id;
    @PositiveOrZero
    private Long version;
}
