package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scione.scm.bill.domain.contract.Contract;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 合同详情响应。
 */
@Data
public class ContractDetailResponse {

    private Long id;
    private String contractNo;
    private String contractName;
    private Integer contractType;
    private String purchaseOrderNo;
    private Integer sourceType;

    // 供应商信息
    private Long supplierId;
    private String supplierName;
    private String supplierPhone;

    // 需方公司信息
    private Long buyerCompanyId;
    private String buyerCompanyName;
    private String buyerCompanyCode;

    // 金额信息
    private BigDecimal originalAmount;
    private BigDecimal discountedAmount;
    private BigDecimal contractAmount;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate contractDate;

    // 状态信息
    private Integer status;
    private String statusText;

    // 签署信息
    private String fadadaTaskId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime signStartTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime signCompleteTime;

    // 文件 URL
    private String contractPdfUrl;
    private String signedPdfUrl;

    // 创建信息
    private String creatorId;
    private String creatorName;
    private Integer createType;
    private String createTypeText;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    // 合同明细
    private List<ContractItemDTO> items;

    public static ContractDetailResponse from(Contract contract) {
        ContractDetailResponse dto = new ContractDetailResponse();
        dto.setId(contract.getId());
        dto.setContractNo(contract.getContractNo());
        dto.setContractName(contract.getContractName());
        dto.setContractType(contract.getContractType());
        dto.setPurchaseOrderNo(contract.getPurchaseOrderNo());
        dto.setSourceType(contract.getSourceType());

        dto.setSupplierId(contract.getSupplierId());
        dto.setSupplierName(contract.getSupplierName());
        dto.setSupplierPhone(contract.getSupplierPhone());

        dto.setBuyerCompanyId(contract.getBuyerCompanyId());
        dto.setBuyerCompanyName(contract.getBuyerCompanyName());
        dto.setBuyerCompanyCode(contract.getBuyerCompanyCode());

        dto.setOriginalAmount(contract.getOriginalAmount());
        dto.setDiscountedAmount(contract.getDiscountedAmount());
        dto.setContractAmount(contract.getContractAmount());
        dto.setContractDate(contract.getContractDate());

        dto.setStatus(contract.getStatus().getCode());
        dto.setStatusText(contract.getStatus().getDesc());

        dto.setCreatorId(contract.getCreatorId());
        dto.setCreatorName(contract.getCreatorName());
        dto.setCreateType(contract.getCreateType());
        dto.setCreateTypeText(contract.getCreateType() == 1 ? "自动创建" : "手动创建");

        // 文件 URL 映射
        dto.setContractPdfUrl(contract.getContractPdfUrl());
        dto.setSignedPdfUrl(contract.getSignedPdfUrl());

        // 明细转换
        if (contract.getItems() != null) {
            dto.setItems(contract.getItems().stream()
                    .map(ContractItemDTO::from)
                    .collect(Collectors.toList()));
        }

        return dto;
    }
}