package com.scione.scm.bill.application.port;

/** 将已填充的合同工作簿转换为 PDF。具体实现负责模板排版及图片渲染。 */
public interface ContractPdfConverter {

    byte[] convert(byte[] xlsxBytes, String contractNo);
}
