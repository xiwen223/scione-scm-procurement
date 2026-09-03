package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

/**
 * 采购交付预警详情标题查询结果。
 */
@Data
public class DeliveryAlertDetailPO {

    private String groupKey;
    private String productName;
}
