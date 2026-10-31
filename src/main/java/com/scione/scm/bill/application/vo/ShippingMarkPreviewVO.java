package com.scione.scm.bill.application.vo;

/**
 * 箱唛预览所需的标签字段和 Code 128 图片。
 */
public record ShippingMarkPreviewVO(
        Long id,
        String purchaseOrderNo,
        String skuCode,
        String skuName,
        String skuImage,
        String barcodeImage,
        boolean requiredFields) {
}
