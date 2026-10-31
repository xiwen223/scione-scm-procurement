package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 页面已选择的外部编号，仅用于同步消息，不写入货品名称字段；导入不使用此对象。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CompanyGoodsSyncSelectionDTO {
    /** 领星供应商ID，与当前supplier_name配对。 */
    @Positive
    @JsonDeserialize(using = CompanyGoodsLongDeserializer.class)
    private Long supplierId;
    /** 与product_owner_names中的姓名按顺序一一对应。 */
    private List<@Positive Long> productOwnerIds;
    /** 黑湖工艺路线编号。 */
    @Size(max = 128)
    private String processRoutingCode;
    /** 黑湖仓库编号。 */
    @Size(max = 128)
    private String warehouseCode;
    /** 领星质检模板ID。 */
    @Size(max = 128)
    private String qcTemplateId;
}
