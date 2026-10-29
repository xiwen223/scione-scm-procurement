package com.scione.scm.bill.application.port;

import java.util.Optional;
import java.util.List;

/**
 * 领星供应商资料查询端口。
 */
public interface LingxingSupplierClient {

    /**
     *  实时分页查询全部供应商，仅保留系统ID与名称。
     *
     * @return 完整供应商ID及名称列表
     */
    List<SupplierSummary> findAllSuppliers();

    record SupplierSummary(long supplierId, String supplierName) {
    }

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
