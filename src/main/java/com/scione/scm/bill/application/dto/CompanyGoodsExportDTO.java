package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

/** 导出勾选的主货品，包含其一层子货品。 */
@Data
public class CompanyGoodsExportDTO {
    @NotEmpty
    @Size(max = 2000)
    @JsonDeserialize(contentUsing = CompanyGoodsLongDeserializer.class)
    private List<@NotNull @Positive Long> ids;
}
