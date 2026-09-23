package com.scione.scm.bill.application.port;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 领星商品实时查询端口。
 */
public interface LingxingProductClient {

    Optional<ProductDetail> findBySku(String sku);

    /**
     * 批量按 SKU 查询商品明细，一次请求查多个 SKU。
     *
     * <p>领星商品的 {@code batchGetProductInfo} 本身接受 {@code skus} 数组，
     * 按明细逐条调用会把一次详情查询放大成 N 次 HTTP（还容易触发限流），
     * 所以需要多 SKU 时一律走这里。</p>
     *
     * @param skus 待查 SKU（自动去重、忽略空值）
     * @return sku → 商品明细；查不到或未返回的 SKU 不会出现在结果里
     */
    Map<String, ProductDetail> findBySkus(Collection<String> skus);

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
