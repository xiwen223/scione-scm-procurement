package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.application.dto.CompanyGoodsQueryDTO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 企业货品访问，关联货品按 SKU 批量获取。 */
public interface CompanyGoodsMapper {
    long count(@Param("query") CompanyGoodsQueryDTO query);
    List<CompanyGoodsPO> findPage(@Param("query") CompanyGoodsQueryDTO query, @Param("offset") long offset);
    List<CompanyGoodsPO> lockByProductIdAndGoodsName(@Param("productId") String productId, @Param("goodsName") String goodsName);
    List<Long> findProductParentIds(@Param("productId") String productId, @Param("id") Long id);
    List<CompanyGoodsPO> lockProductFamily(@Param("productId") String productId);
    List<CompanyGoodsPO> findByProductIds(@Param("productIds") List<String> productIds);
    CompanyGoodsPO lockById(@Param("id") Long id);
    int updateSkuRelations(@Param("goods") CompanyGoodsPO goods, @Param("expectedVersion") Long expectedVersion);
    CompanyGoodsPO findById(@Param("id") Long id);
    List<CompanyGoodsPO> findBySkus(@Param("skus") List<String> skus);
    List<CompanyGoodsPO> findExportParents(@Param("ids") List<Long> ids);
    List<Long> findParentIds(@Param("sku") String sku, @Param("id") Long id);
    List<Long> lockNameConflicts(@Param("names") List<String> names, @Param("ids") List<Long> ids);
    int update(@Param("goods") CompanyGoodsPO goods, @Param("expectedVersion") Long expectedVersion);
    List<CompanyGoodsPO> lockImportConflicts(@Param("sku") String sku, @Param("name") String name);
    int insertImport(@Param("goods") CompanyGoodsPO goods);
    int insertLocal(@Param("goods") CompanyGoodsPO goods);
    /** 导入更新货品资料及改码后的已有引用，不重建主子关系和产品类型。 */
    int updateImport(@Param("goods") CompanyGoodsPO goods, @Param("expectedVersion") Long expectedVersion);
    int modifyLocal(@Param("goods") CompanyGoodsPO goods, @Param("expectedVersion") Long expectedVersion);
}
