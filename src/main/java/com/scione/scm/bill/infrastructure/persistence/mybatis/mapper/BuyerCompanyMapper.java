package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.BuyerCompanyPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

public interface BuyerCompanyMapper {

    int insert(BuyerCompanyPO company);

    int update(BuyerCompanyPO company);

    int deleteById(@Param("id") Long id);

    /** 仅更新签章（图片 + 印章名称），避免列表页单独维护签章时覆盖公司其他字段 */
    int updateSeal(@Param("id") Long id, @Param("sealUrl") String sealUrl, @Param("sealName") String sealName);

    Optional<BuyerCompanyPO> findById(@Param("id") Long id);

    long count(
            @Param("keyword") String keyword,
            @Param("isActive") Integer isActive,
            @Param("defaultOnly") boolean defaultOnly);

    List<BuyerCompanyPO> findPage(
            @Param("keyword") String keyword,
            @Param("isActive") Integer isActive,
            @Param("defaultOnly") boolean defaultOnly,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    boolean existsByCreditCode(
            @Param("creditCode") String creditCode,
            @Param("excludeId") Long excludeId);

    boolean existsByOpenCorpId(
            @Param("openCorpId") String openCorpId,
            @Param("excludeId") Long excludeId);

    boolean existsDefaultExcept(@Param("id") Long id);

    int clearDefaultExcept(@Param("id") Long id);

    /**
     * 查询默认需方公司（priority=1 且 is_active=1）。
     */
    BuyerCompanyPO selectDefault();
}
