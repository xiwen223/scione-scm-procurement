package com.scione.scm.bill.domain.shippingmark;

import com.scione.scm.bill.domain.shippingmark.enums.DetailStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 箱唛明细实体（对应 shipping_mark_detail 表）。
 */
@Getter
public class ShippingMarkDetail {

    @Setter
    private Long id;
    private final String billNo;
    private final String purchaseOrderNo;
    private final String skuCode;
    private final String skuName;
    private final String skuImage;
    private DetailStatus status;
    private String errorReason;
    private String labelFile;
    private final String createdBy;
    private final String creator;
    private LocalDateTime createdAt;
    private String updatedBy;
    private String updator;
    private LocalDateTime updatedAt;

    public ShippingMarkDetail(String billNo, String purchaseOrderNo, String skuCode,
                              String skuName, String skuImage, String createdBy, String creator) {
        this.billNo = billNo;
        this.purchaseOrderNo = purchaseOrderNo;
        this.skuCode = skuCode;
        this.skuName = skuName;
        this.skuImage = skuImage;
        this.status = DetailStatus.PENDING;
        this.createdBy = createdBy;
        this.creator = creator;
        this.createdAt = LocalDateTime.now();
        this.updatedBy = createdBy;
        this.updator = creator;
        this.updatedAt = this.createdAt;
    }

    /**
     * 由持久化适配器重建实体，保留数据库中的审计字段和处理状态。
     */
    public static ShippingMarkDetail rehydrate(Long id, String billNo, String purchaseOrderNo,
                                               String skuCode, String skuName, String skuImage,
                                               Integer status, String errorReason, String labelFile,
                                               String createdBy, String creator, LocalDateTime createdAt,
                                               String updatedBy, String updator, LocalDateTime updatedAt) {
        ShippingMarkDetail detail = new ShippingMarkDetail(
                billNo, purchaseOrderNo, skuCode, skuName, skuImage, createdBy, creator);
        detail.id = id;
        detail.status = status == null ? DetailStatus.PENDING : DetailStatus.of(status);
        detail.errorReason = errorReason;
        detail.labelFile = labelFile;
        detail.createdAt = createdAt;
        detail.updatedBy = updatedBy;
        detail.updator = updator;
        detail.updatedAt = updatedAt;
        return detail;
    }

    public void start() {
        if (status != DetailStatus.PENDING) {
            throw new IllegalStateException("仅待处理明细可开始生成");
        }
        this.status = DetailStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    public void markSuccess(String labelFile) {
        this.status = DetailStatus.SUCCESS;
        this.errorReason = null;
        this.labelFile = labelFile;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(String reason) {
        this.status = DetailStatus.FAILED;
        this.errorReason = reason;
        this.labelFile = null;
        this.updatedAt = LocalDateTime.now();
    }
}
