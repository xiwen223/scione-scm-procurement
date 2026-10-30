package com.scione.scm.bill.application.port;

import java.util.List;

/** 跟单 Excel 解析和错误工作簿生成端口，文件存储复用通用文件服务。 */
public interface DeliveryFollowUpWorkbook {
    /** 解析上传的Excel：返回有效行和错误行；错误行不阻断有效行。 */
    Parsed parse(String fileName, byte[] content);

    /** 把错误行生成一份可下载的错误Excel。 */
    byte[] errorFile(List<RowError> errors);

    /** 一行输入：rowNumber原文件行号、values六列单元格值。 */
    record InputRow(int rowNumber, List<String> values) {
    }

    /** 一行错误：rowNumber原文件行号、values原值、message错误原因。 */
    record RowError(int rowNumber, List<String> values, String message) {
    }

    /** 解析结果：rows有效行、errors错误行。 */
    record Parsed(List<InputRow> rows, List<RowError> errors) {
    }
}
