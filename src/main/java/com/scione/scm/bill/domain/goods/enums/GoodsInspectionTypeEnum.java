package com.scione.scm.bill.domain.goods.enums;

/** 货品检验类型，使用固定业务编码。 */
public enum GoodsInspectionTypeEnum {
    INCOMING_INSPECTION(1, "来料检验"),
    FINISHED_GOODS_RECEIPT_INSPECTION(2, "成品入库检验"),
    SALES_SHIPMENT_INSPECTION(3, "销售发货检验"),
    OTHER_INSPECTION(4, "其他检验");

    private final int code;
    private final String name;

    GoodsInspectionTypeEnum(int code, String name) {
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
