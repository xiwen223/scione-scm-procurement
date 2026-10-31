package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

/** 创建主货品及完整子货品，整体成功或回滚。 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CompanyGoodsCreateDTO extends CompanyGoodsSaveDTO {
    @Valid
    private List<@NotNull @Valid CompanyGoodsChildCreateDTO> groupList;
}
