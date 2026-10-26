package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/** 主、子 SKU 表单整体保存；group_list 仅携带现有子 SKU 的可编辑字段。 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CompanyGoodsUpdateDTO extends CompanyGoodsEditDTO {
    @NotNull
    private List<@NotNull @Valid CompanyGoodsEditDTO> groupList;
}
