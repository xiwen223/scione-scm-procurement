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
    public static final String TYPE_SKIP_CREATE = "SKIP_CREATE";

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
                                                String operatorId, String operatorName,
                                                String desc, String details) {
        ContractOperationLog log = new ContractOperationLog();
        log.contractId = contractId;
        log.contractNo = contractNo;
        log.operatorId = operatorId;
        log.operatorName = operatorName;
        log.operationType = TYPE_CREATE;
        log.operationDesc = desc;
        log.operationDetails = details;
        return log;
    }

    /**
     * 构建一条「修改合同」日志。
     */
    public static ContractOperationLog ofUpdate(Long contractId, String contractNo,
                                                String operatorId, String operatorName,
                                                String desc, String details) {
        ContractOperationLog log = new ContractOperationLog();
        log.contractId = contractId;
        log.contractNo = contractNo;
        log.operatorId = operatorId;
        log.operatorName = operatorName;
        log.operationType = TYPE_MODIFY;
        log.operationDesc = desc;
        log.operationDetails = details;
        return log;
    }

    /**
     * 自动建单跳过时合同尚未落库，以 contractId=0、contractNo=PO 单号记录原因。
     */
    public static ContractOperationLog ofCreateSkipped(String purchaseOrderNo, String details) {
        ContractOperationLog log = new ContractOperationLog();
        log.contractId = 0L;
        log.contractNo = purchaseOrderNo;
        log.operatorId = Contract.SYSTEM_OPERATOR;
        log.operatorName = Contract.SYSTEM_OPERATOR;
        log.operationType = TYPE_SKIP_CREATE;
        log.operationDesc = "自动创建采购合同跳过";
        log.operationDetails = details;
        return log;
    }

    public static ContractOperationLog ofUrgeSign(Long contractId, String contractNo,
                                                   String operatorId, String operatorName, String taskId) {
        ContractOperationLog log = new ContractOperationLog();
        log.contractId = contractId;
        log.contractNo = contractNo;
        log.operatorId = operatorId;
        log.operatorName = operatorName;
        log.operationType = TYPE_URGE_SIGN;
        log.operationDesc = "催办签署";
        log.operationDetails = "法大大签署任务ID：" + taskId;
        return log;
    }

    public static ContractOperationLog ofStartSign(Long contractId, String contractNo,
                                                    String operatorId, String operatorName, String taskId,
                                                    String consistencyDetails) {
        ContractOperationLog log = new ContractOperationLog();
        log.contractId = contractId;
        log.contractNo = contractNo;
        log.operatorId = operatorId;
        log.operatorName = operatorName;
        log.operationType = TYPE_START_SIGN;
        log.operationDesc = "发起合同签署";
        log.operationDetails = "合同状态：创建 → 签署中；法大大任务ID=" + taskId
                + "；我方免验证自动盖章；已向供应商发送签署短信"
                + (consistencyDetails == null || consistencyDetails.isBlank() ? "" : "；" + consistencyDetails);
        return log;
    }

    public static ContractOperationLog ofCancel(Long contractId, String contractNo,
                                                String operatorId, String operatorName, String oldStatus,
                                                String reason, String taskId) {
        ContractOperationLog log = new ContractOperationLog();
        log.contractId = contractId;
        log.contractNo = contractNo;
        log.operatorId = operatorId;
        log.operatorName = operatorName;
        log.operationType = TYPE_CANCEL;
        log.operationDesc = "作废合同";
        log.operationDetails = "合同状态：" + oldStatus + " → 取消；作废原因="
                + (reason == null || reason.isBlank() ? "未填写" : reason)
                + (taskId == null || taskId.isBlank() ? "" : "；法大大任务ID=" + taskId + "已撤销");
        return log;
    }
}
