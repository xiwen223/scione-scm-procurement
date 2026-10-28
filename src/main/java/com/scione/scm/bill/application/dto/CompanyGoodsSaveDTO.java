package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 企业商品库主、子货品共有字段；字段均可为空。 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CompanyGoodsSaveDTO {
    /** 产品类型由上游提供：0普通产品，1组合产品。 */
    @Min(0) @Max(1)
    @JsonDeserialize(using = CompanyGoodsIntegerDeserializer.class)
    private Integer productType;
    @Size(max = 128)
    private String sku;
    @Size(max = 128)
    private String productId;
    @Size(max = 128)
    private String asin;
    @Size(max = 32)
    private String clearanceCountryCode;
    @Valid
    private List<@NotNull @Valid CompanyGoodsImageDTO> pictureList;
    @Size(max = 300)
    private String goodsName;
    @Min(0) @Max(1)
    @JsonDeserialize(using = CompanyGoodsIntegerDeserializer.class)
    private Integer syncScope;
    @Size(max = 32)
    private String unit;
    @Size(max = 128)
    private String developer;
    @Min(0) @Max(2)
    @JsonDeserialize(using = CompanyGoodsIntegerDeserializer.class)
    private Integer productAttribute;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cgProductLength;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cgProductWidth;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cgProductHeight;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal netWeight;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
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
    @Positive
    @JsonDeserialize(using = CompanyGoodsIntegerDeserializer.class)
    private Integer cartonQuantity;
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
    private BigDecimal cartonWeight;
    @Size(max = 128)
    private String processRoute;
    @Size(max = 128)
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
    @Min(1) @Max(3)
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
}
