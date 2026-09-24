package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractTemplatePO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

public interface ContractTemplateMapper {

    int insert(ContractTemplatePO template);

    int update(ContractTemplatePO template);

    /** 逻辑删除：把 is_deleted 置 1，不做物理删除；已删除模板不再被任何查询命中。 */
    int deleteById(@Param("id") Long id);

    Optional<ContractTemplatePO> findById(@Param("id") Long id);

    long count(
            @Param("keyword") String keyword,
            @Param("contractType") Integer contractType,
            @Param("isActive") Integer isActive,
            @Param("defaultOnly") boolean defaultOnly);

    List<ContractTemplatePO> findPage(
            @Param("keyword") String keyword,
            @Param("contractType") Integer contractType,
            @Param("isActive") Integer isActive,
            @Param("defaultOnly") boolean defaultOnly,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    boolean existsDefaultExcept(
            @Param("contractType") Integer contractType,
            @Param("id") Long id);

    int clearDefaultExcept(
            @Param("contractType") Integer contractType,
            @Param("id") Long id);
}
