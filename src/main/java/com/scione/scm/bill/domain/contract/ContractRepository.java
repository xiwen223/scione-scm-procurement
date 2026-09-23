package com.scione.scm.bill.domain.contract;

import java.util.Optional;

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
}
