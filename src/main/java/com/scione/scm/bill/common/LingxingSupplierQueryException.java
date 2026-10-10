package com.scione.scm.bill.common;

import lombok.Getter;

/** 供应商查询失败的安全诊断信息，供自动创建日志分类，不包含 token 或请求完整 URL。 */
@Getter
public class LingxingSupplierQueryException extends BusinessException {
    private final String category;
    private final long supplierId;
    private final int attempts;
    private final long elapsedMs;

    public LingxingSupplierQueryException(String category, long supplierId, int attempts,
                                         long elapsedMs, String reason, Throwable safeCause) {
        super(ResultCode.LINGXING_API_ERROR, "分类=" + category
                + "；步骤=查询领星供应商资料；接口=/erp/sc/data/local_inventory/supplier"
                + "；供应商ID=" + supplierId + "；调用次数=" + attempts
                + "；重试次数=" + (attempts - 1) + "；耗时Ms=" + elapsedMs + "；原因=" + reason);
        this.category = category;
        this.supplierId = supplierId;
        this.attempts = attempts;
        this.elapsedMs = elapsedMs;
        if (safeCause != null) initCause(safeCause);
    }
}
