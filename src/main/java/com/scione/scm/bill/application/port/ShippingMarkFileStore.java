package com.scione.scm.bill.application.port;

import java.io.IOException;
import java.io.InputStream;

/**
 * 箱唛图片和标签文件的应用层存储端口。
 */
public interface ShippingMarkFileStore {

    String storeImage(String billNo, byte[] content, String extension) throws IOException;

    String storeLabel(String billNo, byte[] content) throws IOException;

    byte[] read(String storedFileReference) throws IOException;

    /**
     * 打开已存储的文件内容；调用方负责关闭返回的流。
     */
    InputStream open(String storedFileReference) throws IOException;

    /**
     * 返回已存储文件的字节数，用于在流式传输前进行批量大小校验。
     */
    long size(String storedFileReference) throws IOException;

    byte[] load(String billNo, String category, String fileName) throws IOException;
}
