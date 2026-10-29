package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 单个 SKU 可编辑字段；不接收产品类型、来源、状态及关联关系。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CompanyGoodsEditDTO {
    @NotNull @Positive
    @JsonDeserialize(using = CompanyGoodsLongDeserializer.class)
    private Long id;
    @Size(max = 128)
    private String sku;
    /** 客户端读取的版本，防止旧表单覆盖其他人的修改。 */
    @NotNull @PositiveOrZero
    @JsonDeserialize(using = CompanyGoodsLongDeserializer.class)
    private Long version;
    @NotBlank @Size(max = 300)
    private String goodsName;
    /** 子货品为空时由页面更新服务继承主货品本次提交的同步范围。 */
    @Min(0) @Max(1)
    @JsonDeserialize(using = CompanyGoodsIntegerDeserializer.class)
    private Integer syncScope;
    @NotBlank @Size(max = 32)
    private String unit;
    @Size(max = 128)
    private String developer;
    @NotNull @Min(0) @Max(2)
    @JsonDeserialize(using = CompanyGoodsIntegerDeserializer.class)
    private Integer productAttribute;
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cgProductLength;
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cgProductWidth;
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cgProductHeight;
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal netWeight;
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal grossWeight;
    @DecimalMin("0") @Digits(integer = 16, fraction = 2)
    private BigDecimal purchaseCost;
    @Size(max = 64)
    private String supplierName;
    /** 供应商不含税报价，单位元，最多两位小数。 */
    @DecimalMin("0") @Digits(integer = 16, fraction = 2)
    private BigDecimal supplierQuote;
    @Size(max = 32)
    private String purchaserName;
    @Size(max = 512)
    private String productOwnerNames;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cgBoxLength;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cgBoxWidth;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cgBoxHeight;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cgPackageLength;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cgPackageWidth;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cgPackageHeight;
    @NotNull @Positive
    @JsonDeserialize(using = CompanyGoodsIntegerDeserializer.class)
    private Integer cartonQuantity;
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cartonWeight;
    @Size(max = 128)
    private String processRoute;
    @NotBlank @Size(max = 128)
    private String productCategory;
    @Size(max = 4)
    private List<@NotBlank String> inspectionType;
    @Size(max = 128)
    private String incomingInspectionStandard;
    @Size(max = 128)
    private String defaultWarehouse;
    @Min(0) @Max(1)
    @JsonDeserialize(using = CompanyGoodsIntegerDeserializer.class)
    private Integer isKeyPart;
    @NotNull @Min(1) @Max(3)
    @JsonDeserialize(using = CompanyGoodsIntegerDeserializer.class)
    private Integer qualityInspectionMethod;
    @Size(max = 128)
    private String qualityTemplate;
    @Size(max = 512)
    private String customsNameCn;
    @Size(max = 512)
    private String customsNameEn;
    @Size(max = 512)
    private String materialCn;
    @Size(max = 512)
    private String materialEn;
    @Size(max = 512)
    private String usageCn;
    @Size(max = 512)
    private String usageEn;
    @Size(max = 32) @Pattern(regexp = "[0-9]*", message = "HSCODE 仅允许数字")
    private String customsDeclarationHsCode;
    /** USD；不允许数据库静默舍入。 */
    @DecimalMin("0") @Digits(integer = 16, fraction = 2)
    private BigDecimal customsImportPrice;
    @Size(max = 32) @Pattern(regexp = "[0-9]*", message = "HSCODE 仅允许数字")
    private String bgImportHsCode;
    @DecimalMin("0") @Digits(integer = 16, fraction = 2)
    private BigDecimal clearancePrice;
    /** 未更换时传原图片对象键（历史图片传原链接）；新图传压缩 data URI，保存时上传 S3；null 表示移除。 */
    @Size(max = 200000)
    private String imageUrl;
}
