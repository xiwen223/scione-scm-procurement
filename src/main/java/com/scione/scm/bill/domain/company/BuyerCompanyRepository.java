package com.scione.scm.bill.domain.company;

import java.util.Optional;

/**
 * 需方公司仓储接口。
 */
public interface BuyerCompanyRepository {

    /**
     * 查询默认需方公司（is_active=1 按 priority 升序取第一条）。
     *
     * @return 默认需方公司
     */
    Optional<BuyerCompany> findDefault();
}