package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.application.dto.CompanyGoodsQueryDTO;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 企业货品访问；展示按产品ID及关系名称加载子货品，导出及改码引用维护保留SKU查询。 */
public interface CompanyGoodsMapper {
    /**
     * 统计符合查询条件的主货品数量，子货品命中时归属到主货品。
     *
     * @param query 货品筛选及分页条件
     * @return 满足条件的主货品总数
     */
    long count(@Param("query") CompanyGoodsQueryDTO query);
    /**
     * 按条件及偏移量查询当前页主货品。
     *
     * @param query 货品筛选及分页条件
     * @param offset 分页偏移量，从0开始
     * @return 按查询排序规则返回的主货品列表
     */
    List<CompanyGoodsPO> findPage(@Param("query") CompanyGoodsQueryDTO query, @Param("offset") long offset);
    /**
     * 按产品ID与货品名称定位并锁定记录，用于导入和商品库修改。
     *
     * @param productId 企业商品ID，对应数据库product_id
     * @param goodsName 货品名称，对应数据库goods_name
     * @return 匹配并加行锁的货品列表，由业务层校验唯一性
     */
    List<CompanyGoodsPO> lockByProductIdAndGoodsName(@Param("productId") String productId, @Param("goodsName") String goodsName);
    /**
     * 查询同产品ID下除指定记录外的组合主货品主键。
     *
     * @param productId 企业商品ID，对应数据库product_id
     * @param id 需要排除的当前货品数据库主键
     * @return 关联组合主货品的数据库主键列表
     */
    List<Long> findProductParentIds(@Param("productId") String productId, @Param("id") Long id);
    /**
     * 查询产品ID相同且关系数组中包含当前货品名称的展示主货品。
     *
     * @param id 当前货品数据库主键
     * @return 当前货品实际归属的组合主货品主键
     */
    List<Long> findDisplayParentIds(@Param("id") Long id);
    /**
     * 按产品ID锁定整组货品，供主货品唯一性校验。
     *
     * @param productId 企业商品ID，对应数据库product_id
     * @return 同产品ID下已加锁的货品列表
     */
    List<CompanyGoodsPO> lockProductFamily(@Param("productId") String productId);
    /**
     * 批量按产品ID查询货品，用于展示关联或导入字典预加载。
     *
     * @param productIds 需要批量查询的企业商品ID集合
     * @return 属于指定产品ID集合的货品列表
     */
    List<CompanyGoodsPO> findByProductIds(@Param("productIds") List<String> productIds);
    /**
     * 按数据库主键查询并锁定单条货品。
     *
     * @param id 货品数据库主键
     * @return 匹配的货品记录；不存在时返回null
     */
    CompanyGoodsPO lockById(@Param("id") Long id);
    /**
     * 按主键及原版本更新关联JSON并递增版本。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @param expectedVersion 更新前应匹配的数据库版本号
     * @return 受影响行数，成功更新单条记录时为1
     */
    int updateSkuRelations(@Param("goods") CompanyGoodsPO goods, @Param("expectedVersion") Long expectedVersion);
    /**
     * 按数据库主键读取货品完整记录。
     *
     * @param id 货品数据库主键
     * @return 货品记录；不存在时返回null
     */
    CompanyGoodsPO findById(@Param("id") Long id);
    /**
     * 按SKU集合批量读取货品。
     *
     * @param skus 待匹配的SKU编码集合
     * @return 匹配SKU的货品列表
     */
    List<CompanyGoodsPO> findBySkus(@Param("skus") List<String> skus);
    /**
     * 读取勾选且可作为导出主行的货品。
     *
     * @param ids 当前操作涉及的货品数据库主键集合
     * @return 可导出的主货品记录列表
     */
    List<CompanyGoodsPO> findExportParents(@Param("ids") List<Long> ids);
    /**
     * 按查询条件读取全部可作为导出主行的货品主键，不受分页限制。
     *
     * @param query 货品筛选条件，复用列表查询的 filters 片段
     * @return 满足条件且可作为导出主行的货品数据库主键列表，按更新时间、主键倒序
     */
    List<Long> findExportParentIdsByQuery(@Param("query") CompanyGoodsQueryDTO query);
    /**
     * 查找group_list中引用指定SKU且不包含自身的父货品。
     *
     * @param sku 货品SKU编码
     * @param id 需要从父记录查询结果中排除的当前货品主键
     * @return 引用该SKU的主货品数据库主键列表
     */
    List<Long> findParentIds(@Param("sku") String sku, @Param("id") Long id);
    /**
     * 锁定名称重复且不属于当前编辑记录的货品。
     *
     * @param names 待匹配的货品名称集合
     * @param ids 本次允许保留自身名称的货品主键集合
     * @return 名称冲突的货品数据库主键列表
     */
    List<Long> lockNameConflicts(@Param("names") List<String> names, @Param("ids") List<Long> ids);
    /**
     * 按数据库主键及原版本保存页面编辑字段并递增版本。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @param expectedVersion 更新前应匹配的数据库版本号
     * @return 受影响行数，版本不匹配时为0
     */
    int update(@Param("goods") CompanyGoodsPO goods, @Param("expectedVersion") Long expectedVersion);
    /**
     * 锁定SKU或名称匹配的记录，供保存前唯一性校验。
     *
     * @param sku 货品SKU编码
     * @param name 需要检查唯一性的货品名称
     * @return 可能发生SKU或名称冲突的货品列表
     */
    List<CompanyGoodsPO> lockImportConflicts(@Param("sku") String sku, @Param("name") String name);
    /**
     * 新增导入货品记录，由MyBatis回填数据库主键。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @return 插入行数
     */
    int insertImport(@Param("goods") CompanyGoodsPO goods);
    /**
     * 新增本地货品并使用创建默认状态，由MyBatis回填主键。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @return 插入行数
     */
    int insertLocal(@Param("goods") CompanyGoodsPO goods);
    /**
     *  导入更新货品资料及改码后的已有引用，不重建主子关系和产品类型。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @param expectedVersion 更新前应匹配的数据库版本号
     * @return 受影响行数，版本不匹配时为0
     */
    int updateImport(@Param("goods") CompanyGoodsPO goods, @Param("expectedVersion") Long expectedVersion);
    /**
     * 按主键及原版本保存商品库修改字段并递增版本。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @param expectedVersion 更新前应匹配的数据库版本号
     * @return 受影响行数，版本不匹配时为0
     */
    int modifyLocal(@Param("goods") CompanyGoodsPO goods, @Param("expectedVersion") Long expectedVersion);
}
