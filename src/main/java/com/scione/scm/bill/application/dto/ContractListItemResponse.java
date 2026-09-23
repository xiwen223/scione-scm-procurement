package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.contract.Contract;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 合同列表项响应。
 */
@Data
public class ContractListItemResponse {

    private Long id;
    private String contractNo;
    private String contractName;
    private String purchaseOrderNo;
    private String supplierName;
    private String buyerCompanyName;
    private BigDecimal contractAmount;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate contractDate;

    private Integer status;
    private String statusText;

    private Integer createType;
    private String createTypeText;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    public static ContractListItemResponse from(Contract contract) {
        ContractListItemResponse dto = new ContractListItemResponse();
        dto.setId(contract.getId());
        dto.setContractNo(contract.getContractNo());
        dto.setContractName(contract.getContractName());
        dto.setPurchaseOrderNo(contract.getPurchaseOrderNo());
        dto.setSupplierName(contract.getSupplierName());
        dto.setBuyerCompanyName(contract.getBuyerCompanyName());
        dto.setContractAmount(contract.getContractAmount());
        dto.setContractDate(contract.getContractDate());
        dto.setStatus(contract.getStatus().getCode());
        dto.setStatusText(contract.getStatus().getDesc());
        dto.setCreateType(contract.getCreateType());
        dto.setCreateTypeText(contract.getCreateType() == 1 ? "自动创建" : "手动创建");
        // createTime 需要从 PO 获取，暂时留空，后续在查询时补充
        return dto;
    }
}