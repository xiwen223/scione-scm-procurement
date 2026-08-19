package com.scione.scm.bill.application.port;

import java.io.IOException;

/**
 * 箱唛图片和标签文件的应用层存储端口。
 */
public interface ShippingMarkFileStore {

    String storeImage(String billNo, byte[] content, String extension) throws IOException;

    String storeLabel(String billNo, byte[] content) throws IOException;

    byte[] read(String storedFileReference) throws IOException;

    byte[] load(String billNo, String category, String fileName) throws IOException;
}
