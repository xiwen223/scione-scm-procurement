package com.scione.scm.bill.application.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 企业货品列表项，使用文档约定的下划线 JSON 字段。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CompanyGoodsListItemVO {
    /** 当前主 SKU 下关联的子 SKU 数量；主 SKU 无此关联数量。 */
    private BigDecimal quantity;
    /** 货品主键 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 当前货品SKU，不支持编辑； */
    private String sku;

    /** 当前货品对应产品id，不支持编辑； */
    private String productId;

    /** 当前SKU名称，业务必填；去重范围待确认 */
    private String goodsName;

    /** 产品类型：0普通产品，1组合产品 */
    private Integer productType;

    /** 同步范围：0领星，1领星黑湖 */
    private Integer syncScope;

    /** 产品单位，主子SKU分别记录，业务必填 */
    private String unit;

    /** 开发人名称 */
    private String developer;

    /** 产品属性：默认0，0自制，1外购，2委外 */
    private Integer productAttribute;

    /** 创建状态：0待创，1已创；异常通过独立标记展示。 */
    private Integer goodsStatus;

    /** 字段冲突状态：默认0，0正常，1字段冲突 */
    private Integer conflictStatus;

    /** SKU异常状态：默认0，0正常，1黑湖存在且领星不存在 */
    private Integer exceptionStatus;

    /** 领星采购员名称，业务必填 */
    private String purchaserName;

    /** 领星产品负责人名称，业务必填，支持多人，多个名称用英文逗号分隔 */
    private String productOwnerNames;

    /** 黑湖产品分类标识，业务必填 */
    private String productCategory;

    /** 领星默认质检方式：默认1，1抽检，2全检，3免检 */
    private Integer qualityInspectionMethod;

    /** 修改时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedTime;

    /** 货品主图链接，无图时为 null。 */
    private String imageUrl;

    /** 当前货品的一层子 SKU 列表。 */
    private List<CompanyGoodsListItemVO> groupList;

}
