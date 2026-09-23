package com.scione.scm.bill.infrastructure.persistence.mybatis.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProcurementOperationLogPO {
    private Long id;
    private Integer businessType;
    private Long dataId;
    private String dataName;
    private Long operatorId;
    private String operatorName;
    private String operationType;
    private String operationDesc;
    private String operationDetails;
    private String ipAddress;
    private LocalDateTime createTime;
}
