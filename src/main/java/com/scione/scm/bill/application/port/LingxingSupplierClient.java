package com.scione.scm.bill.application.port;

import java.util.Optional;

/**
 * 领星供应商资料查询端口。
 */
public interface LingxingSupplierClient {

    /**
     * 查询供应商启用的默认收款账号。
     */
    Optional<SupplierPaymentAccount> findDefaultPaymentAccount(long supplierId);

    Optional<SupplierProfile> findSupplierProfile(long supplierId);

    /**
     * accountName = 领星「供应商-收款账户」的「账户名称」（name 字段，通常为公司全称），
     * 注意不是「户名」（account_name 字段，通常为个人姓名）。
     */
    record SupplierPaymentAccount(String accountName, String accountId, String bankName) {
    }

    record SupplierProfile(String address, String creditCode, String prepayPercent, String settlementMethod,
                           Optional<SupplierPaymentAccount> defaultPaymentAccount) {
    }
}
