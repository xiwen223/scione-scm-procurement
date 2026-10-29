package com.scione.scm.bill.application.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/** 领星后台产品查询结果。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class LingxingProductVO {
    /** 货品SKU。 */
    private String sku;
    /** 产品名称。 */
    private String productName;
    /** 采购价格，保留领星返回的小数文本格式。 */
    private String cgPrice;
}
