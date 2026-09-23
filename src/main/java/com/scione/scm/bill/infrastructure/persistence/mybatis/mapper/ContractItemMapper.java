package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractItemPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 合同明细 Mapper。
 */
public interface ContractItemMapper {

    /**
     * 批量插入明细（回填主键 id）。
     */
    int batchInsert(@Param("items") List<ContractItemPO> items);

    /**
     * 按合同 ID 查询明细列表。
     */
    List<ContractItemPO> selectByContractId(@Param("contractId") Long contractId);
}