package com.scione.scm.bill.application.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/** 领星网页后台供应商查询结果，仅保留供应商ID及名称。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class LingxingSupplierVO {
    /** 领星供应商ID。 */
    private Long supplierId;
    /** 供应商名称。 */
    private String supplierName;
}
