package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PoSyncRecordItemPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * PO单明细 Mapper。
 */
public interface PoSyncRecordItemMapper {

    /** 按采购单号查询明细列表。 */
    List<PoSyncRecordItemPO> selectByOrderNo(@Param("purchaseOrderNo") String purchaseOrderNo);

    /** 按采购单号删除该单全部明细。 */
    int deleteByOrderNo(@Param("purchaseOrderNo") String purchaseOrderNo);

    /** 批量插入明细。 */
    int batchInsert(@Param("items") List<PoSyncRecordItemPO> items);
}