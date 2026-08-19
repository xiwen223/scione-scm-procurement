package com.scione.scm.bill.application.dto;

/**
 * 箱唛预览所需的标签字段和 Code 128 图片。
 */
public record ShippingMarkPreviewDTO(
        Long id,
        String purchaseOrderNo,
        String skuCode,
        String skuName,
        String skuImage,
        String barcodeImage) {
}
