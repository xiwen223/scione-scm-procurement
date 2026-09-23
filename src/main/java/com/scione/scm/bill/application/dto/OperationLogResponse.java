package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志响应。
 */
@Data
public class OperationLogResponse {

    private Long id;
    private String operatorName;
    private String operationType;
    private String operationDesc;
    private String operationDetails;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}