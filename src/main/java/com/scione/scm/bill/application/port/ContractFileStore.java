package com.scione.scm.bill.application.port;

import java.io.IOException;

/**
 * 合同文件存储服务（端口）。
 */
public interface ContractFileStore {

    /**
     * 存储合同文件，返回访问 URL。
     *
     * @param contractNo 合同编号
     * @param fileBytes  文件字节数组
     * @param extension  文件扩展名（如 "xlsx"）
     * @return 文件访问 URL
     */
    String store(String contractNo, byte[] fileBytes, String extension) throws IOException;

    /**
     * 从 S3 下载合同文件。
     *
     * @param fileUrl 文件 URL（来自 contract_pdf_url 或 signed_pdf_url）
     * @return 文件字节数组
     */
    byte[] download(String fileUrl) throws IOException;
}