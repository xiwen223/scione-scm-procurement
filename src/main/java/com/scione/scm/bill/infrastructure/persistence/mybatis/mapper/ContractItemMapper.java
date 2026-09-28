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

    /** 查询相同 SKU 最近一次成功保存的商品图片 URL。 */
    String selectLatestPicUrlBySku(@Param("sku") String sku);

    /**
     * 按 ID 更新明细。
     *
     * @param item 明细 PO
     * @return 影响行数
     */
    int updateById(ContractItemPO item);
}
