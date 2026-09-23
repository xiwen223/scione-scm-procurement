package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ProcurementOperationLogPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ProcurementOperationLogMapper {

    long count(
            @Param("dataName") String dataName,
            @Param("businessType") Integer businessType,
            @Param("operationType") String operationType,
            @Param("operator") String operator,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    List<ProcurementOperationLogPO> findPage(
            @Param("dataName") String dataName,
            @Param("businessType") Integer businessType,
            @Param("operationType") String operationType,
            @Param("operator") String operator,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);
}
