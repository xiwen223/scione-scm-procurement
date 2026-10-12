package com.scione.scm.bill.application.port;

import java.io.IOException;

/**
 * 合同文件存储服务（端口）。
 */
public interface ContractFileStore {

    /**
     * 存储合同文件，返回持久对象键。
     *
     * @param contractNo 合同编号
     * @param fileBytes  文件字节数组
     * @param extension  文件扩展名（如 "xlsx"）
     * @return 文件对象键
     */
    String store(String contractNo, byte[] fileBytes, String extension) throws IOException;

    /**
     * 通过数据平台下载合同文件。
     *
     * @param objectKey 数据平台返回的对象键（来自 contract_pdf_url 或 signed_pdf_url）
     * @return 文件字节数组
     */
    byte[] download(String objectKey) throws IOException;
}
