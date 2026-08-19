package com.scione.scm.bill.application.dto;

import java.util.List;

/**
 * Excel 纯解析结果：不落库，仅返回解析出的箱唛明细。
 */
public record ShippingMarkParseDTO(String fileName, List<Detail> details) {

    public record Detail(String purchaseOrderNo, String skuCode, String skuName, String imageBase64) {
    }
}
