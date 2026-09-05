package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

/**
 * 采购交付预警看板套装组件明细查询结果。
 * 字段与主查询 innerSelect 保持一致，并按原型补充明细所需字段。
 */
@Data
public class DeliveryAlertComponentPO {

    private Long componentId;
    private String sku;
    private String productName;
    private String orderSn;
    private String planSn;
    private String purchaseMode;
    private String supplierName;
    private String warehouseName;
    private String optRealname;
    private Integer quantityPlan;
    private Integer quantityReal;
    private Integer quantityEntry;
    private Integer expectedQty;
    private Integer qualifiedQty;
    private Integer returnedQty;
    private String statusText;
    private Integer risk;
}
