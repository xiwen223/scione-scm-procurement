package com.scione.scm.bill.application.vo;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** 批量导入结果；失败文件保留模板列，可修改后重新上传。 */
@Data
public class CompanyGoodsImportVO {
    private int successCount;
    private int failureCount;
    private List<RowResultVO> records = new ArrayList<>();
    private String failedFileBase64;

    @Data
    public static class RowResultVO {
        private int rowNumber;
        private String sku;
        private boolean success;
        private String message;
    }
}
