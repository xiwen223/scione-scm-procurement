package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractOperationLogPO;

/**
 * 合同操作日志 Mapper。
 */
public interface ContractOperationLogMapper {

    /**
     * 插入操作日志。
     */
    int insert(ContractOperationLogPO log);
}