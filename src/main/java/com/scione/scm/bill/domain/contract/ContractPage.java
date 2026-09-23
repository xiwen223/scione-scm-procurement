package com.scione.scm.bill.domain.contract;

import java.util.List;

/**
 * 合同分页查询结果。
 */
public record ContractPage(long total, List<Contract> records) {
}