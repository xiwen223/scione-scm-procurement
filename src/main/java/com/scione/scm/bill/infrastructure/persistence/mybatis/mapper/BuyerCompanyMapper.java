package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.BuyerCompanyPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

public interface BuyerCompanyMapper {

    int insert(BuyerCompanyPO company);

    int update(BuyerCompanyPO company);

    int deleteById(@Param("id") Long id);

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
