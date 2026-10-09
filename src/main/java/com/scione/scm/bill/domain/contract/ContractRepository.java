package com.scione.scm.bill.domain.contract;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
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
     * 查询指定采购单号名下已有的「有效合同」（未取消、未逻辑删除），取最新一条。
     *
     * <p>与 {@link #existsActiveByPurchaseOrderNo(String)} 判定口径一致（status != 5 且 is_deleted = 0），
     * 但额外返回合同编号与状态，用于在填单阶段就给出「已存在合同 HTxxxx（签署中）」这类可读提示。</p>
     *
     * @param purchaseOrderNo 采购单号
     * @return 有效合同引用；没有则 empty
     */
    Optional<ActiveContractRef> findActiveByPurchaseOrderNo(String purchaseOrderNo);

    /**
     * 按合同编号查询（用于 generateContractNo 去重）。
     *
     * @param contractNo 合同编号
     * @return 合同聚合（含明细）
     */
    Optional<Contract> findByContractNo(String contractNo);

    /** 按法大大签署任务 ID 查询合同，兼容原签署任务和解除协议任务回调。 */
    Optional<Contract> findByFadadaTaskId(String taskId);

    /** 查询合同关联的法大大解除协议任务 ID。 */
    Optional<String> findFadadaAbolishedTaskId(long contractId);

    /**
     * 合同编号唯一性校验（排除自身）。
     * 不过滤逻辑删除的行 —— 它们仍占用唯一索引，复用编号会直接写库失败。
     *
     * @param contractNo        待校验的合同编号
     * @param excludeContractId 排除的合同 ID（自身）
     * @return true=已被其它合同占用
     */
    boolean existsContractNo(String contractNo, long excludeContractId);

    /**
     * 合同编号变更后同步所有冗余引用：contract_item.contract_no 与
     * procurement_operation_log.data_name（合同业务）。主表编号本身由 {@link #update} 写入。
     *
     * @param contractId 合同 ID
     * @param contractNo 新的合同编号
     */
    void updateContractNoReferences(long contractId, String contractNo);

    /**
     * 更新合同的 PDF URL（模板填充后回写）。
     */
    void updatePdfUrl(long contractId, String pdfUrl);

    void markSigning(long contractId, String fadadaTaskId);

    void cancel(long contractId, String cancelReason);

    /** 保存法大大解除协议任务，原合同仍待解除协议完成；作废原因（选填）一并落库，供详情页展示。 */
    void markFadadaAbolishPending(long contractId, String abolishedTaskId, String cancelReason);

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
     * 批量版 {@link #findLatestItemPicUrlBySku(String)}：一次查询取回多个 SKU 的最新图片，
     * 避免详情查询按明细逐条查库。
     *
     * @param skus SKU 集合（为空时直接返回空 Map）
     * @return sku → 图片 URL（查不到或图片为空的 SKU 不在结果里）
     */
    Map<String, String> findLatestItemPicUrlsBySkus(Collection<String> skus);

    /**
     * 详情查询补齐商品图片后回写单条明细的图片 URL。
     *
     * <p>只更新 {@code pic_url} 一列，不影响明细其他字段；库内已有图片时不覆盖。
     * 落库后后续详情查询与合同 PDF 生成可直接复用，不必每次再查一次领星。</p>
     *
     * @param itemId 明细 ID
     * @param picUrl 商品图片 URL
     */
    void updateItemPicUrl(long itemId, String picUrl);

    /**
     * 更新合同（合同主表 + 明细）。
     *
     * @param contract 待更新的合同聚合（含明细）
     */
    void update(Contract contract);

    /** 仅更新合同主表，不更新商品明细。 */
    void updateMain(Contract contract);

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

    /**
     * 批量取每个合同「最近一次催办供方签署」的时间，用于列表展示。
     * 数据来源是操作日志（催办不落合同表字段），没有催办记录的合同不在返回结果里。
     *
     * @param contractIds 合同 ID 集合，为空时直接返回空 Map
     * @return 合同 ID → 最近一次催办时间
     */
    Map<Long, LocalDateTime> findLatestUrgeTimes(Collection<Long> contractIds);
}
