package com.scione.scm.bill.domain.shippingmark;

import com.scione.scm.bill.domain.shippingmark.enums.DetailStatus;
import com.scione.scm.bill.domain.shippingmark.enums.MarkStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 箱唛导入单聚合根（对应 shipping_mark_import 主表）。
 */
@Getter
public class ShippingMark {

    @Setter
    private Long id;
    private final String billNo;
    private final String billName;
    private MarkStatus status;
    private String createdBy;
    private String creator;
    private LocalDateTime createdAt;
    private String updatedBy;
    private String updator;
    private LocalDateTime updatedAt;
    private LocalDateTime processedAt;
    private final List<ShippingMarkDetail> details = new ArrayList<>();

    private ShippingMark(String billNo, String billName) {
        this.billNo = billNo;
        this.billName = billName;
    }

    public static ShippingMark create(String billNo, String billName, String createdBy, String creator) {
        ShippingMark mark = new ShippingMark(billNo, billName);
        mark.status = MarkStatus.PENDING;
        mark.createdBy = createdBy;
        mark.creator = creator;
        mark.updatedBy = createdBy;
        mark.updator = creator;
        mark.createdAt = LocalDateTime.now();
        mark.updatedAt = mark.createdAt;
        return mark;
    }

    /**
     * 由持久化适配器重建聚合，避免领域层依赖 MyBatis 或持久化对象。
     */
    public static ShippingMark rehydrate(Long id, String billNo, String billName, Integer status,
                                         String createdBy, String creator, LocalDateTime createdAt,
                                         String updatedBy, String updator, LocalDateTime updatedAt,
                                         LocalDateTime processedAt, List<ShippingMarkDetail> details) {
        ShippingMark mark = new ShippingMark(billNo, billName);
        mark.id = id;
        mark.status = status == null ? MarkStatus.PENDING : MarkStatus.of(status);
        mark.createdBy = createdBy;
        mark.creator = creator;
        mark.createdAt = createdAt;
        mark.updatedBy = updatedBy;
        mark.updator = updator;
        mark.updatedAt = updatedAt;
        mark.processedAt = processedAt;
        mark.details.addAll(details);
        return mark;
    }

    public void addDetail(ShippingMarkDetail detail) {
        details.add(detail);
    }

    /** 提交导入任务后进入处理中。 */
    public void start() {
        if (status != MarkStatus.PENDING) {
            throw new IllegalStateException("仅待处理单据可开始处理");
        }
        this.status = MarkStatus.PROCESSING;
        touch();
    }

    /** 所有明细都已成功或失败后，导入单才能完成。 */
    public void complete() {
        boolean hasUnprocessedDetail = details.stream()
                .anyMatch(detail -> detail.getStatus() == DetailStatus.PENDING
                        || detail.getStatus() == DetailStatus.PROCESSING);
        if (hasUnprocessedDetail) {
            throw new IllegalStateException("仍有明细正在处理");
        }
        this.status = MarkStatus.DONE;
        this.processedAt = LocalDateTime.now();
        touch();
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
