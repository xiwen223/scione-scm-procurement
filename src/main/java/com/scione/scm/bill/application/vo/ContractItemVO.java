package com.scione.scm.bill.application.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.contract.ContractItem;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 返回前端的合同明细 VO。
 */
@Data
public class ContractItemVO {

    private Long id;
    private String sku;
    private String productName;
    private String specification;
    private Integer quantity;
    private String unit;
    private BigDecimal unitPrice;
    private BigDecimal amount;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate deliveryDate;

    private String warehouseName;
    private String remark;
    private String picUrl;

    public static ContractItemVO from(ContractItem item) {
        ContractItemVO dto = new ContractItemVO();
        dto.setId(item.getId());
        dto.setSku(item.getSku());
        dto.setProductName(item.getProductName());
        dto.setSpecification(item.getSpecification());
        dto.setQuantity(item.getQuantity());
        dto.setUnit(item.getUnit());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setAmount(item.getAmount());
        dto.setDeliveryDate(item.getDeliveryDate());
        dto.setWarehouseName(item.getWarehouseName());
        dto.setRemark(item.getRemark());
        dto.setPicUrl(item.getPicUrl());
        return dto;
    }
}
