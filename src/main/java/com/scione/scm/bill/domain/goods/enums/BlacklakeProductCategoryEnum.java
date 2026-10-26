package com.scione.scm.bill.domain.goods.enums;

/** 本地维护的黑湖产品分类，编码按约定固定，不使用枚举序号。 */
public enum BlacklakeProductCategoryEnum {
    RAW_PLASTIC_FORK(0, "原料塑料叉"),
    RAW_PLASTIC_SPOON(1, "原料塑料勺"),
    RAW_PLASTIC_KNIFE(2, "原料塑料刀"),
    NAPKIN_INK(3, "纸巾油墨"),
    NAPKIN_BASE_PAPER(4, "纸巾原纸"),
    COLORED_NAPKIN(5, "彩色餐巾纸"),
    CUSTOM_NAPKIN(6, "定制纸巾"),
    PAPER_PLATE_INK(7, "餐盘油墨"),
    PAPER_PLATE_BASE_PAPER(8, "餐盘原纸"),
    PAPER_PLATE_GLOSS_FILM(9, "餐盘亮膜"),
    COLORED_PAPER_PLATE(10, "彩色餐盘"),
    CUSTOM_PAPER_PLATE(11, "定制餐盘"),
    TABLECLOTH(12, "桌布"),
    PAPER_CUP(13, "纸杯"),
    OTHER_ACCESSORIES(14, "其他配件"),
    PRINTED_PACKAGING_BOX(15, "包装彩盒"),
    PARTY_SET(16, "派对套装"),
    FREIGHT(17, "运费");

    /** 稳定分类编码，范围 0～17。 */
    private final int code;
    /** 分类名称。 */
    private final String name;

    BlacklakeProductCategoryEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
