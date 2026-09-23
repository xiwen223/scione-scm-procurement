package com.scione.scm.bill.infrastructure.persistence.mybatis.mapper;

import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractTemplatePO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

public interface ContractTemplateMapper {

    int insert(ContractTemplatePO template);

    int update(ContractTemplatePO template);

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
