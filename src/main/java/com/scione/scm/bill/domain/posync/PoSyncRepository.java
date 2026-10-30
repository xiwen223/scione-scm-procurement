package com.scione.scm.bill.domain.posync;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 采购单同步仓储接口。
 */
public interface PoSyncRepository {

    /** 跨实例串行执行自动同步和手动回补，避免旧窗口覆盖新窗口。 */
    <T> T withSyncLock(Supplier<T> action);

    /** 仅自动同步使用；未初始化时为空，不以单据更新时间推测进度。 */
    Optional<LocalDateTime> findUpdatedUntil();

    /** 首次请求前持久化查询下界；即使首轮失败/重启，也不丢掉这个待查窗口。 */
    void initializeUpdatedUntil(LocalDateTime initialBoundary);

    /** 本轮查询、保存和合同处理无失败后，才推进自动同步进度。 */
    void advanceUpdatedUntil(LocalDateTime updatedUntil);

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
