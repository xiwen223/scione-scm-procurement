package com.scione.scm.bill.domain.goods;

import lombok.Data;
import java.math.BigDecimal;

/** 主 SKU 与子 SKU 的关联关系，不表示库存数量。 */
@Data
public class GoodsSkuRelation {
    /** 关联子货品 SKU，允许为空；展示关联使用产品ID，空编码项的数量可按名称匹配。 */
    private String sku;
    /** 关联时记录的子货品名称。 */
    @com.fasterxml.jackson.annotation.JsonProperty("goods_name")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String goodsName;
    /** 主子 SKU 的比例数量，保留数据库精度。 */
    private BigDecimal quantity;
}
