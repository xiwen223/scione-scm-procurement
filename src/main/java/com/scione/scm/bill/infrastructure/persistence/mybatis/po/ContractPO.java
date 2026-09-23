package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * contract 表持久化对象。
 */
@Data
public class ContractPO {
    private Long id;
    private String contractNo;
    private String contractName;
    private Integer contractType;
    private String purchaseOrderNo;
    private Integer sourceType;
    private Long supplierId;
    private String supplierName;
    private String supplierPhone;
    private String supplierCreditCode;
    private String supplierAccountName;
    private String supplierBankName;
    private String supplierBankAccount;
    private String supplierAddress;
    private String contactPerson;
    private Long buyerCompanyId;
    private String buyerCompanyName;
    private String buyerCompanyCode;
    private String buyerAddress;
    private String postCode;
    private String buyerPhone;
    private String fax;
    private BigDecimal originalAmount;
    private BigDecimal discountedAmount;
    private BigDecimal contractAmount;
    private LocalDate contractDate;
    private LocalDate deliveryDate;
    private Long templateId;
    private Integer status;
    private String cancelReason;
    private LocalDateTime cancelTime;
    private String fadadaContractId;
    private String fadadaTaskId;
    private String contractPdfUrl;
    private String signedPdfUrl;
    private LocalDateTime signStartTime;
    private LocalDateTime signCompleteTime;
    private LocalDateTime completeTime;
    private String errorMessage;
    private String creatorId;
    private String creatorName;
    private Integer createType;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer isDeleted;
}