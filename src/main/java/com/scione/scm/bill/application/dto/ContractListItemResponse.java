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
    private String signLaunchState;
    private String signLaunchError;
    private boolean abolishPending;

    private Integer createType;
    private String createTypeText;


    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /**
     * 签署时间：我方（需方）发起签署并自动盖章的时间，取 contract.sign_start_time。
     * 未发起签署时为空；不是供方签署完成时间（那是 sign_complete_time / status=3 的时间）。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime signStartTime;

    /**
     * 催办时间：该合同最近一次催办供方签署的时间。
     * 催办只写操作日志（procurement_operation_log，business_type=1、operation_type=URGE_SIGN）没有独立字段，
     * 由 ContractQueryService 按当前页合同批量取最大值后回填。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastUrgeTime;

    /**
     * @param lastUrgeTime 最近一次催办时间，来自操作日志的批量查询；无催办记录时传 null
     */
    public static ContractListItemResponse from(Contract contract, LocalDateTime lastUrgeTime) {
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
        dto.setSignLaunchState(contract.getSignLaunchState());
        dto.setSignLaunchError(contract.getSignLaunchError());
        dto.setAbolishPending(contract.isAbolishPending());
        dto.setCreateType(contract.getCreateType());
        dto.setCreateTypeText(contract.getCreateType() == 1 ? "自动创建" : "手动创建");
        dto.setCreateTime(contract.getCreateTime());
        dto.setUpdateTime(contract.getUpdateTime());
        dto.setSignStartTime(contract.getSignStartTime());
        dto.setLastUrgeTime(lastUrgeTime);
        return dto;
    }
}
