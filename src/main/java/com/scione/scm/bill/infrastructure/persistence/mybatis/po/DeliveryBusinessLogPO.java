package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * lx_business_log 采购单业务日志查询结果。
 */
@Data
public class DeliveryBusinessLogPO {

    private Long id;
    private String orderSn;
    private LocalDateTime eventTime;
    private String title;
    private String description;
    private String operator;
}
