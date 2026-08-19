package com.scione.scm.bill.application.port;

import com.scione.scm.bill.domain.shippingmark.ShippingMarkDetail;

import java.io.IOException;

/**
 * 渲染箱唛标签和条码的应用层端口。
 */
public interface ShippingMarkLabelRenderer {

    byte[] render(ShippingMarkDetail detail) throws IOException;

    String barcodeDataUrl(String skuCode) throws IOException;
}
