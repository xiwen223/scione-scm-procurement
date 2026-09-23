package com.scione.scm.bill.domain.contract;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 合同操作日志领域模型。对应 contract_operation_log 表。
 */
@Data
public class ContractOperationLog {
    public static final String TYPE_CREATE = "CREATE";
    public static final String TYPE_MODIFY = "MODIFY";
    public static final String TYPE_START_SIGN = "START_SIGN";
    public static final String TYPE_URGE_SIGN = "URGE_SIGN";
    public static final String TYPE_CANCEL = "CANCEL";
    public static final String TYPE_STATUS_CHANGE = "STATUS_CHANGE";

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

    /**
     * 构建一条「自动创建」日志。
     */
    public static ContractOperationLog ofCreate(Long contractId, String contractNo,
                                                String operatorId, String operatorName, String desc) {
        ContractOperationLog log = new ContractOperationLog();
        log.contractId = contractId;
        log.contractNo = contractNo;
        log.operatorId = operatorId;
        log.operatorName = operatorName;
        log.operationType = TYPE_CREATE;
        log.operationDesc = desc;
        return log;
    }
}