package com.scione.scm.bill.application.vo;

import com.scione.scm.bill.domain.shippingmark.ShippingMark;
import com.scione.scm.bill.domain.shippingmark.enums.DetailStatus;
import lombok.Data;

/**
 * 箱唛处理统计。
 */
@Data
public class ProcessResultVO {

    private int processedCount;
    private int successCount;
    private int failCount;

    public static ProcessResultVO from(ShippingMark mark) {
        ProcessResultVO result = new ProcessResultVO();
        result.setSuccessCount((int) mark.getDetails().stream()
                .filter(detail -> detail.getStatus() == DetailStatus.SUCCESS).count());
        result.setFailCount((int) mark.getDetails().stream()
                .filter(detail -> detail.getStatus() == DetailStatus.FAILED).count());
        result.setProcessedCount(result.getSuccessCount() + result.getFailCount());
        return result;
    }
}
