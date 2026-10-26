package com.scione.scm.bill.application.port;

import java.util.List;

/** 跟单 Excel 解析和错误工作簿生成端口，文件存储复用通用文件服务。 */
public interface DeliveryFollowUpWorkbook {
    Parsed parse(String fileName, byte[] content);

    byte[] errorFile(List<RowError> errors);

    record InputRow(int rowNumber, List<String> values) {
    }

    record RowError(int rowNumber, List<String> values, String message) {
    }

    record Parsed(List<InputRow> rows, List<RowError> errors) {
    }
}
