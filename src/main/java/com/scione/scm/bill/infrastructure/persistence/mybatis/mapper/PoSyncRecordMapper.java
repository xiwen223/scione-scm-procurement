package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.PoSyncRecordPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * PO单头 Mapper。
 */
public interface PoSyncRecordMapper {

    /** 按 purchase_order_no 做 upsert（单头幂等）。 */
    int upsert(PoSyncRecordPO record);

    /**
     * 查询待创建合同的 PO（po_status=1 且 contract_created=0）。
     */
    List<PoSyncRecordPO> selectPendingForContract();

    /**
     * 按采购单号列表查询待创建合同的 PO。
     */
    List<PoSyncRecordPO> selectPendingForContractByOrderNos(@Param("orderNos") List<String> orderNos);

    /**
     * 标记 PO 已创建合同。
     */
    int markContractCreated(@Param("purchaseOrderNo") String purchaseOrderNo, @Param("contractId") Long contractId);

    PoSyncRecordPO selectByPurchaseOrderNo(String purchaseOrderNo);
}