package com.scione.scm.bill.application.port;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 领星商品实时查询端口。
 */
public interface LingxingProductClient {

    Optional<ProductDetail> findBySku(String sku);

    record ProductDetail(
            Long id,
            Long cid,
            Long bid,
            String sku,
            String skuIdentifier,
            String productName,
            String picUrl,
            Long cgDelivery,
            BigDecimal cgTransportCosts,
            String purchaseRemark,
            BigDecimal cgPrice,
            Long status,
            Long openStatus,
            Long isCombo,
            Long createTime,
            Long updateTime,
            Long productDeveloperUid,
            Long cgOptUid,
            String cgOptUsername,
            String spu,
            Long psId,
            JsonNode attribute,
            String brandName,
            String categoryName,
            String statusText,
            String productDeveloper,
            JsonNode supplierQuote,
            JsonNode auxRelationList,
            JsonNode customFields,
            JsonNode globalTags,
            List<ComboProduct> comboProductList) {
    }

    record ComboProduct(Long productId, Long quantity, String sku) {
    }
}
