package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractItemPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.math.BigDecimal;
import java.util.Collection;

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

    /**
     * 批量查询每个 SKU 最近一次成功保存的商品图片 URL（一次查询替代逐条查询）。
     * 只回填 sku 与 pic_url 两个字段，其余字段为 null。
     *
     * @param skus SKU 集合（非空）
     */
    List<ContractItemPO> selectLatestPicUrlsBySkus(@Param("skus") Collection<String> skus);

    /**
     * 仅回填明细的商品图片 URL（详情查询补齐图片后落库），不动数量/单价等其余字段。
     * 只在库内图片仍为空时写入，避免覆盖并发补齐的结果。
     *
     * @param id     明细 ID
     * @param picUrl 商品图片 URL
     * @return 影响行数（0=该明细已有图片或无此明细）
     */
    int updatePicUrl(@Param("id") Long id, @Param("picUrl") String picUrl);

    /**
     * 按 ID 更新明细。
     *
     * @param item 明细 PO
     * @return 影响行数
     */
    int updateById(ContractItemPO item);

    int updatePricing(@Param("id") Long id,
                      @Param("quantity") Integer quantity,
                      @Param("unitPrice") BigDecimal unitPrice,
                      @Param("amount") BigDecimal amount);

    /**
     * 合同编号变更时刷新明细中冗余的 contract_no，避免明细与主表编号不一致。
     *
     * @param contractId 合同 ID
     * @param contractNo 新的合同编号
     * @return 影响行数
     */
    int updateContractNoByContractId(@Param("contractId") Long contractId,
                                     @Param("contractNo") String contractNo);
}
