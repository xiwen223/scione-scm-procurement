package com.scione.scm.bill.domain.sop.enums;

import com.fasterxml.jackson.databind.util.StdConverter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/** 统一节点目录；只有节点1使用杭州范围，其他节点不能复用其店铺筛选。 */
public enum NodeCode {
    PO_CREATE("PO创建未完成"), PO_APPROVAL("PO审批未完成"), PO_ORDER("PO下发供应商未完成"),
    CONTRACT_SIGN("合同签章未完成"), RF_CREATE("创建请款单未完成"), RF_APPROVAL("财务审批未完成"),
    RF_PAYMENT("财务付款未完成"), DELIVERY_CONFIRM("跟单确认供应商交期未完成"),
    GOODS_READY("跟单确认供应商货物备齐未完成"), CR_RECEIPT("CR收货未完成"), PACKING_RELATE("关联货件催办");
    private final String title;
    NodeCode(String title) { this.title = title; }
    public String title() { return title; }

    /** 仅在数据库/历史JSON边界保留逗号编码，业务内部使用枚举列表。顺序原样保留以兼容历史轮次键。 */
    public static String join(Collection<NodeCode> nodes) {
        return nodes.stream().map(NodeCode::name).collect(Collectors.joining(","));
    }

    public static List<NodeCode> parseList(String codes) {
        if (codes == null || codes.isBlank()) return List.of();
        return Arrays.stream(codes.split(",", -1)).map(NodeCode::valueOf).toList();
    }

    /** 历史发送上下文的nodeCode字段保持字符串格式，不要求迁移数据库。 */
    public static class CodesToText extends StdConverter<List<NodeCode>, String> {
        @Override public String convert(List<NodeCode> nodes) { return join(nodes); }
    }

    public static class TextToCodes extends StdConverter<String, List<NodeCode>> {
        @Override public List<NodeCode> convert(String codes) { return parseList(codes); }
    }
}
