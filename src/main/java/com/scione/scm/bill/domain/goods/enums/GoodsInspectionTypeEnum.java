package com.scione.scm.bill.domain.goods.enums;

/** 货品检验类型，使用固定业务编码。 */
public enum GoodsInspectionTypeEnum {
    INCOMING_INSPECTION(1, "来料检验"),
    FINISHED_GOODS_RECEIPT_INSPECTION(2, "成品入库检验"),
    SALES_SHIPMENT_INSPECTION(3, "销售发货检验"),
    OTHER_INSPECTION(4, "其他检验");

    private final int code;
    private final String name;

    /**
     * 初始化货品检验类型枚举的稳定业务编码及显示信息。
     *
     * @param code 稳定业务编码，不使用枚举序号
     * @param name 业务显示名称
     */
    GoodsInspectionTypeEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * 取得货品检验类型的稳定业务编码。
     *
     * @return 货品检验类型业务编码
     */
    public int getCode() {
        return code;
    }

    /**
     * 取得货品检验类型的中文显示名称。
     *
     * @return 货品检验类型显示名称
     */
    public String getName() {
        return name;
    }
}
