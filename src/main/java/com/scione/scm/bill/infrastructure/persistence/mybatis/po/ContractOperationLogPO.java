package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * contract_operation_log 表持久化对象。
 */
@Data
public class ContractOperationLogPO {
    private Long id;
    private Long contractId;
    private String contractNo;
    private String operatorId;
    private String operatorName;
    private String operationType;
    private String operationDesc;
    private String operationDetails;
    private String ipAddress;
    private LocalDateTime createTime;
}