package com.scione.scm.bill.infrastructure.persistence.mybatis;

import com.scione.scm.bill.domain.company.BuyerCompany;
import com.scione.scm.bill.domain.company.BuyerCompanyRepository;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.BuyerCompanyMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.BuyerCompanyPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 基于 MyBatis 的需方公司仓储适配器。
 */
@Repository
@RequiredArgsConstructor
public class MybatisBuyerCompanyRepository implements BuyerCompanyRepository {

    private final BuyerCompanyMapper mapper;

    @Override
    public Optional<BuyerCompany> findDefault() {
        BuyerCompanyPO po = mapper.selectDefault();
        if (po == null) {
            return Optional.empty();
        }
        return Optional.of(toDomain(po));
    }

    private BuyerCompany toDomain(BuyerCompanyPO po) {
        BuyerCompany domain = new BuyerCompany();
        domain.setId(po.getId());
        domain.setCompanyName(po.getCompanyName());
        domain.setCompanyShortName(po.getCompanyShortName());
        domain.setCreditCode(po.getCreditCode());
        domain.setLegalPerson(po.getLegalPerson());
        domain.setAddress(po.getAddress());
        domain.setPhone(po.getPhone());
        domain.setBankName(po.getBankName());
        domain.setBankAccount(po.getBankAccount());
        domain.setSealUrl(po.getSealUrl());
        domain.setFadadaSealId(po.getFadadaSealId());
        domain.setPriority(po.getPriority());
        domain.setIsActive(po.getIsActive());
        domain.setCreateTime(po.getCreateTime());
        domain.setUpdateTime(po.getUpdateTime());
        return domain;
    }
}