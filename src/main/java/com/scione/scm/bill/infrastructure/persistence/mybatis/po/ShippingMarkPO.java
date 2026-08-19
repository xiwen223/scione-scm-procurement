package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * shipping_mark_import 表持久化对象。
 */
@Data
public class ShippingMarkPO {

    private Long id;
    private String billNo;
    private String billName;
    private Integer status;
    private String createdBy;
    private String creator;
    private LocalDateTime createdAt;
    private String updatedBy;
    private String updator;
    private LocalDateTime updatedAt;
    private LocalDateTime processedAt;
}
