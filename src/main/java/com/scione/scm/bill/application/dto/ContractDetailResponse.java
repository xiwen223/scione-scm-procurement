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
    private String supplierAddress;
    private String contactPerson;
    private String supplierCreditCode;
    private String supplierBankAccount;
    private String supplierBankName;

    // 需方公司信息
    private Long buyerCompanyId;
    private String buyerCompanyName;
    private String buyerCompanyCode;
    private String buyerAddress;
    private String postCode;
    private String buyerPhone;
    private String fax;

    // 结算约定
    /** 预付款比例，与 ContractCreateRequest.prepaymentRatio 同源，如 "0.3" 表示 30% */
    private String prepayPercent;
    private String settlementMethod;

    // 金额信息
    private BigDecimal originalAmount;
    private BigDecimal discountedAmount;
    private BigDecimal contractAmount;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate contractDate;

    /** 交货日期（合同级，由单据明细中最早的交货日汇总而来） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate deliveryDate;

    // 状态信息
    private Integer status;
    private String statusText;
    private String signLaunchState;
    private String signLaunchError;

    // 作废信息（选填的原因 + 作废时间），详情页在状态为「取消」时展示
    private String cancelReason;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime cancelTime;

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
        dto.setSupplierAddress(contract.getSupplierAddress());
        dto.setContactPerson(contract.getContactPerson());
        dto.setSupplierCreditCode(contract.getSupplierCreditCode());
        dto.setSupplierBankAccount(contract.getSupplierBankAccount());
        dto.setSupplierBankName(contract.getSupplierBankName());

        dto.setBuyerCompanyId(contract.getBuyerCompanyId());
        dto.setBuyerCompanyName(contract.getBuyerCompanyName());
        dto.setBuyerCompanyCode(contract.getBuyerCompanyCode());
        dto.setBuyerAddress(contract.getBuyerAddress());
        dto.setPostCode(contract.getPostCode());
        dto.setBuyerPhone(contract.getBuyerPhone());
        dto.setFax(contract.getFax());

        dto.setPrepayPercent(contract.getPrepayPercent());
        dto.setSettlementMethod(contract.getSettlementMethod());
        dto.setDeliveryDate(contract.getDeliveryDate());

        dto.setOriginalAmount(contract.getOriginalAmount());
        dto.setDiscountedAmount(contract.getDiscountedAmount());
        dto.setContractAmount(contract.getContractAmount());
        dto.setContractDate(contract.getContractDate());

        dto.setStatus(contract.getStatus().getCode());
        dto.setStatusText(contract.getStatus().getDesc());
        dto.setSignLaunchState(contract.getSignLaunchState());
        dto.setSignLaunchError(contract.getSignLaunchError());
        dto.setCancelReason(contract.getCancelReason());
        dto.setCancelTime(contract.getCancelTime());

        // 签署信息
        dto.setFadadaTaskId(contract.getFadadaTaskId());
        dto.setSignStartTime(contract.getSignStartTime());
        dto.setSignCompleteTime(contract.getSignCompleteTime());

        dto.setCreatorId(contract.getCreatorId());
        dto.setCreatorName(contract.getCreatorName());
        dto.setCreateType(contract.getCreateType());
        dto.setCreateTypeText(contract.getCreateType() == 1 ? "自动创建" : "手动创建");
        dto.setCreateTime(contract.getCreateTime());
        dto.setUpdateTime(contract.getUpdateTime());

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
