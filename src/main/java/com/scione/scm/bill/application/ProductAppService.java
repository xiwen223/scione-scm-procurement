package com.scione.scm.bill.application;

import com.scione.scm.bill.application.dto.ProductDetailDTO;
import com.scione.scm.bill.application.port.LingxingProductClient;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 商品实时查询应用服务。
 */
@Service
@RequiredArgsConstructor
public class ProductAppService {

    private final LingxingProductClient productClient;

    public ProductDetailDTO findDetail(String sku) {
        String normalizedSku = sku.trim();
        return productClient.findBySku(normalizedSku)
                .map(ProductDetailDTO::from)
                .orElseThrow(() -> new BusinessException(ResultCode.PRODUCT_NOT_FOUND));
    }
}
