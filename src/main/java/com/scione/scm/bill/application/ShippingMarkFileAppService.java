package com.scione.scm.bill.application;

import com.scione.scm.bill.application.port.ShippingMarkFileStore;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 加载已保存箱唛文件的用例。
 */
@Service
@RequiredArgsConstructor
public class ShippingMarkFileAppService {

    private final ShippingMarkFileStore fileStore;

    public byte[] load(String billNo, String category, String fileName) {
        try {
            return fileStore.load(billNo, category, fileName);
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.SHIPPING_MARK_FILE_NOT_FOUND);
        }
    }
}
