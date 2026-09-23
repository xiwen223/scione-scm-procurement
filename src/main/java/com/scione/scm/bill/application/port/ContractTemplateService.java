package com.scione.scm.bill.application.port;

import com.scione.scm.bill.domain.contract.Contract;

/**
 * 合同模板填充服务（端口）。
 */
public interface ContractTemplateService {

    /**
     * 填充合同模板，返回生成的 Excel 文件字节数组。
     */
    byte[] fillTemplate(Contract contract);
}