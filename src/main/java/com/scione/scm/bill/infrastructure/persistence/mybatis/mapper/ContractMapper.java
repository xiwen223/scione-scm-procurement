package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 合同主表 Mapper。
 */
public interface ContractMapper {

    /**
     * 插入合同（回填主键 id）。
     */
    int insert(ContractPO contract);

    /**
     * 唯一性校验：查询指定采购单号是否已有非取消状态的合同。
     *
     * @param purchaseOrderNo 采购单号
     * @return 存在返回 1，不存在返回 0
     */
    int existsActiveByPurchaseOrderNo(@Param("purchaseOrderNo") String purchaseOrderNo);

    /**
     * 按合同编号查询。
     *
     * @param contractNo 合同编号
     * @return 合同 PO
     */
    ContractPO findByContractNo(@Param("contractNo") String contractNo);

    /**
     * 更新合同的 PDF URL。
     *
     * @param id 合同 ID
     * @param pdfUrl PDF 文件 URL
     * @return 影响行数
     */
    int updatePdfUrl(@Param("id") Long id, @Param("pdfUrl") String pdfUrl);

    /**
     * 分页查询合同列表（不含明细）。
     *
     * @param request 查询条件
     * @return 合同 PO 列表
     */
    List<ContractPO> selectByPage(@Param("req") com.scione.scm.bill.application.dto.ContractListQueryRequest request);

    /**
     * 统计查询条件下的合同总数。
     *
     * @param request 查询条件
     * @return 总数
     */
    long countByCondition(@Param("req") com.scione.scm.bill.application.dto.ContractListQueryRequest request);

    /**
     * 按 ID 查询合同。
     *
     * @param id 合同 ID
     * @return 合同 PO
     */
    ContractPO selectById(@Param("id") Long id);
}