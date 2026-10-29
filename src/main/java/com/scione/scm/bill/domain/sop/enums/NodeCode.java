package com.scione.scm.bill.domain.sop.enums;

/** 统一节点目录；只有节点1使用杭州范围，其他节点不能复用其店铺筛选。 */
public enum NodeCode {
    PO_CREATE("PO创建未完成"), PO_APPROVAL("PO审批未完成"), PO_ORDER("PO下发供应商未完成"),
    CONTRACT_SIGN("合同签章未完成"), RF_CREATE("创建请款单未完成"), RF_APPROVAL("财务审批未完成"),
    RF_PAYMENT("财务付款未完成"), DELIVERY_CONFIRM("跟单确认供应商交期未完成"),
    GOODS_READY("跟单确认供应商货物备齐未完成"), CR_RECEIPT("CR收货未完成"), PACKING_RELATE("关联货件催办");
    private final String title;
    NodeCode(String title) { this.title = title; }
    public String title() { return title; }
}
