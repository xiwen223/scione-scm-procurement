package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

/**
 * 导出企业货品请求。
 *
 * <p>支持两种导出范围，二者二选一：
 * <ul>
 *   <li>传入 {@code ids}：按勾选的主货品导出（保留原有行为）；</li>
 *   <li>不传 {@code ids}：按当前查询条件导出全部主货品，不受分页限制。</li>
 * </ul>
 * 当 {@code ids} 为空时，以下查询条件字段生效，语义与列表查询一致。
 * 查询字段使用下划线 JSON 键，与前端列表查询的参数名保持一致。
 */
@Data
public class CompanyGoodsExportDTO {
    /** 勾选的主货品ID，可选；为空时按查询条件导出。 */
    @Size(max = 2000)
    @JsonDeserialize(contentUsing = CompanyGoodsLongDeserializer.class)
    private List<@Positive Long> ids;
    /** 主、子 SKU 模糊查询。 */
    private String sku;
    /** 主、子货品名称模糊查询。 */
    @JsonProperty("goods_name")
    private String goodsName;
    /** 产品属性：0 自制，1 外购，2 委外。 */
    @JsonProperty("product_attribute")
    private Integer productAttribute;
    /** 主货品产品类型：0 普通，1 组合。 */
    @JsonProperty("product_type")
    private Integer productType;
    /** 展示状态：0 待创，1 已创，2 异常。 */
    @JsonProperty("goods_status")
    private Integer goodsStatus;
    /** 产品分类标识，精确匹配。 */
    @JsonProperty("product_category")
    private String productCategory;
    /** 开发人、采购员、负责人任一匹配。 */
    @JsonProperty("personnel_name")
    private String personnelName;
}
