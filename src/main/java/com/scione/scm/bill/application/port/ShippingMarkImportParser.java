package com.scione.scm.bill.application.port;

import java.util.List;

/**
 * 解析箱唛导入文档的应用层端口。
 */
public interface ShippingMarkImportParser {

    ParsedImport parse(ImportDocument document);

    record ImportDocument(String fileName, byte[] content) {
    }

    record ParsedImport(String fileName, List<ParsedDetail> details) {
    }

    record ParsedDetail(String purchaseOrderNo, String skuCode, String skuName, ImageData image) {
    }

    record ImageData(byte[] content, String extension) {
    }
}
