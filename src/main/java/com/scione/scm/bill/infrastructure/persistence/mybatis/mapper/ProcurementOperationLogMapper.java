package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ProcurementOperationLogPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ProcurementOperationLogMapper {

    /** 写入一条操作日志；由 {@code ProcurementOperationLogRecorder} 统一调用。 */
    int insert(ProcurementOperationLogPO log);

    /**
     * 合同编号变更后同步日志中的编号快照（business_type = 1 合同）。
     * 按 data_id（合同ID）定位，不会影响以 PO 单号占位的自动建单跳过日志。
     *
     * @param contractId 合同 ID
     * @param contractNo 新的合同编号
     * @return 影响行数
     */
    int updateDataNameByContractId(@Param("contractId") Long contractId,
                                   @Param("contractNo") String contractNo);

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

    /**
     * 批量取合同最近一次催办（URGE_SIGN）的时间，用于合同列表的「催办时间」列。
     * 只返回 data_id 与聚合出来的 create_time，调用方自行按合同 ID 建映射。
     *
     * @param contractIds 当前页的合同 ID，调用方保证非空
     */
    List<ProcurementOperationLogPO> selectLatestUrgeTimes(@Param("contractIds") Collection<Long> contractIds);
}
