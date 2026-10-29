package com.scione.scm.bill.application.dto;

import com.scione.common.model.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 企业货品查询条件，入口将文档下划线参数映射为 Java 属性。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CompanyGoodsQueryDTO extends PageQuery {
    /** 主、子 SKU 模糊查询。 */
    private String sku;
    /** 主、子货品名称模糊查询。 */
    private String goodsName;
    /** 产品属性：0 自制，1 外购，2 委外。 */
    private Integer productAttribute;
    /** 主货品产品类型：0 普通，1 组合。 */
    private Integer productType;
    /** 展示状态：0 待创，1 已创，2 异常。 */
    private Integer goodsStatus;
    /** 产品分类标识，精确匹配。 */
    private String productCategory;
    /** 开发人、采购员、负责人任一匹配。 */
    private String personnelName;
}
