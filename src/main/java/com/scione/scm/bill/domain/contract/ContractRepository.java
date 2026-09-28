package com.scione.scm.bill.domain.contract;

import java.util.Optional;
import java.util.List;

/**
 * 合同仓储接口（端口）。
 */
public interface ContractRepository {

    /**
     * 原子保存合同（合同主表 + 明细 + 一条 CREATE 操作日志）。
     *
     * @param contract 待保存的合同聚合（含明细）
     * @return 回填的合同 ID
     */
    long create(Contract contract);

    /**
     * 唯一性校验：查询指定采购单号是否已有非取消状态的合同。
     *
     * @param purchaseOrderNo 采购单号
     * @return true=存在，false=不存在
     */
    boolean existsActiveByPurchaseOrderNo(String purchaseOrderNo);

    /**
     * 按合同编号查询（用于 generateContractNo 去重）。
     *
     * @param contractNo 合同编号
     * @return 合同聚合（含明细）
     */
    Optional<Contract> findByContractNo(String contractNo);

    /**
     * 更新合同的 PDF URL（模板填充后回写）。
     */
    void updatePdfUrl(long contractId, String pdfUrl);

    void markSigning(long contractId, String fadadaTaskId);

    void cancel(long contractId, String cancelReason);

    /** 保存法大大解除协议任务，原合同仍待解除协议完成。 */
    void markFadadaAbolishPending(long contractId, String abolishedTaskId);

    /** 法大大作废回调完成后，标记正式作废并更新业务状态。 */
    void markFadadaAbolished(long contractId, String cancelReason);

    void markExecuting(long contractId);
    void markCompleted(long contractId);
    List<Contract> findExecutingContracts();

    void updateSignedPdfUrl(long contractId, String signedPdfUrl);

    /**
     * 分页查询合同列表。
     *
     * @param request 查询条件
     * @return 分页结果
     */
    ContractPage findByPage(com.scione.scm.bill.application.dto.ContractListQueryRequest request);

    /**
     * 按 ID 查询合同详情（含明细）。
     *
     * @param contractId 合同 ID
     * @return 合同聚合
     */
    Optional<Contract> findById(Long contractId);

    /**
     * 按 SKU 复用历史合同明细已经保存的商品图片，避免重复请求领星商品接口。
     */
    Optional<String> findLatestItemPicUrlBySku(String sku);

    /**
     * 更新合同（合同主表 + 明细）。
     *
     * @param contract 待更新的合同聚合（含明细）
     */
    void update(Contract contract);

    /** 仅更新合同金额字段，用于折扣调整，避免无关明细逐条 UPDATE。 */
    void updateAmounts(Contract contract);

    /** 仅更新一条合同明细的数量、单价与金额。 */
    void updateItemPricing(ContractItem item);

    /**
     * 保存操作日志。
     *
     * @param log 操作日志
     */
    void saveOperationLog(ContractOperationLog log);
}
