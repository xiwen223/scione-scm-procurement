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

/** 企业货品详情，使用文档约定的下划线 JSON 字段。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CompanyGoodsDetailVO {
    /** 当前图片的持久对象键；前端保存未更换的图片时提交该键，不保存临时访问地址。 */
    private String imageObjectKey;
    /** 当前主 SKU 下关联的子 SKU 数量；主 SKU 无此关联数量。 */
    private BigDecimal quantity;
    /** 货品主键 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 当前货品SKU，不支持编辑； */
    private String sku;

    /** 当前货品对应商品asin，不支持编辑； */
    private String asin;

    /** 当前货品对应产品id，不支持编辑； */
    private String productId;

    /** 当前SKU名称，业务必填；去重范围待确认 */
    private String goodsName;

    /** 产品类型：0普通产品，1组合产品 */
    private Integer productType;

    /** 同步范围：0领星，1领星黑湖 */
    private Integer syncScope;

    /** 数据来源，元素为企业商品库、领星、黑湖；合并来源且不重复 */
    private List<String> sourceList;

    /** 一层关联子货品，子货品的 group_list 不继续递归展开 */
    private List<CompanyGoodsDetailVO> groupList;

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

    /** 同步异常状态：默认0，0无异常标记，1外部同步异常 */
    private Integer exceptionStatus;

    /** 创建方式：0导入、1商品库接入、2外部同步 */
    private Integer createMethod;

    /** 货品数据版本，用于关联同步消息，业务变更时递增 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long version;

    /** 单品长度，单位CM，业务必填；精度暂按两位小数，待确认 */
    private BigDecimal cgProductLength;

    /** 单品宽度，单位CM，业务必填；精度暂按两位小数，待确认 */
    private BigDecimal cgProductWidth;

    /** 单品高度，单位CM，业务必填；精度暂按两位小数，待确认 */
    private BigDecimal cgProductHeight;

    /** 单品净重，单位g，业务必填 */
    private BigDecimal netWeight;

    /** 单品毛重，单位g */
    private BigDecimal grossWeight;

    /** 采购成本，单位元；同步黑湖与否待确认 */
    private BigDecimal purchaseCost;

    /** 供应商名称，最长64字符。 */
    private String supplierName;

    /** 供应商报价，单位元，业务必填；（领星不含税单价） */
    private BigDecimal supplierQuote;

    /** 领星采购员名称，业务必填 */
    private String purchaserName;

    /** 领星产品负责人名称，业务必填，支持多人，多个名称用英文逗号分隔 */
    private String productOwnerNames;

    /** 外箱长度，单位CM，两位小数 */
    private BigDecimal cgBoxLength;

    /** 外箱宽度，单位CM，两位小数 */
    private BigDecimal cgBoxWidth;

    /** 外箱高度，单位CM，两位小数 */
    private BigDecimal cgBoxHeight;

    /** 包装长度，单位CM，两位小数 */
    private BigDecimal cgPackageLength;

    /** 包装宽度，单位CM，两位小数 */
    private BigDecimal cgPackageWidth;

    /** 包装高度，单位CM，两位小数 */
    private BigDecimal cgPackageHeight;

    /** 单箱数量，单位pcs，业务必填，必须为正整数 */
    private Integer cartonQuantity;

    /** 单箱重量，单位kg，业务必填 */
    private BigDecimal cartonWeight;

    /** 黑湖工艺路线标识，按外部字典校验 */
    private String processRoute;

    /** 黑湖产品分类标识，业务必填 */
    private String productCategory;

    /** 黑湖检验类型编码数组，枚举多选 */
    private List<String> inspectionType;

    /** 黑湖来料检验规范标识 */
    private String incomingInspectionStandard;

    /** 黑湖默认仓库标识 */
    private String defaultWarehouse;

    /** 是否关键部位：0否、1是，NULL未填写 */
    private Integer isKeyPart;

    /** 领星默认质检方式：默认1，1抽检，2全检，3免检 */
    private Integer qualityInspectionMethod;

    /** 领星质检模板名称 */
    private String qualityTemplate;

    /** 中文报关名 */
    private String customsNameCn;

    /** 英文报关名 */
    private String customsNameEn;

    /** 中文材质 */
    private String materialCn;

    /** 英文材质 */
    private String materialEn;

    /** 中文用途 */
    private String usageCn;

    /** 英文用途 */
    private String usageEn;

    /** 报关HSCODE，使用字符串保留前导零 */
    private String customsDeclarationHsCode;

    /** 报关单价，单位 USD。 */
    private BigDecimal customsImportPrice;

    /** 清关国家，枚举下拉，保存外部国家标识 */
    private String clearanceCountryCode;

    /** 默认清关HSCODE，使用字符串保留前导零 */
    private String bgImportHsCode;

    /** 默认清关单价，单位 USD。 */
    private BigDecimal clearancePrice;

    /** 货品图片数组，pic_url 为链接，is_primary 为主图标记 */
    private List<CompanyGoodsPictureVO> pictureList;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdTime;

    /** 修改时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedTime;

    /** 货品主图链接，无图时为 null。 */
    private String imageUrl;

}
