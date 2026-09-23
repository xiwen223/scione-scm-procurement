package com.scione.scm.bill.domain.posync;

import java.util.List;
import java.util.Optional;

/**
 * 采购单同步仓储接口。
 */
public interface PoSyncRepository {

    /** 保存一条采购单（单头 upsert + 明细全量替换）。 */
    void save(PoSyncRecord record);

    /**
     * 查询待创建合同的 PO（全表扫描）。
     * 条件：po_status=1 且 has_contract=0 且供应商信息完整
     */
    List<PoSyncRecord> findPendingForContract();

    /**
     * 查询待创建合同的 PO（按订单号过滤）。
     * 条件：po_status=1 且 has_contract=0 且 purchase_order_no IN (...)
     */
    List<PoSyncRecord> findPendingForContractByOrderNos(List<String> purchaseOrderNos);

    /**
     * 标记 PO 已创建合同。
     *
     * @param purchaseOrderNo 采购单号
     * @param contractId 合同 ID
     */
    void markContractCreated(String purchaseOrderNo, long contractId);

    Optional<PoSyncRecord> findByPurchaseOrderNo(String purchaseOrderNo);

}