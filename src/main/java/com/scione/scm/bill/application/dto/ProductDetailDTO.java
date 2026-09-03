package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.scione.scm.bill.application.port.LingxingProductClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品详情响应。
 */
public record ProductDetailDTO(
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
        List<ComboProductDTO> comboProductList) {

    public static ProductDetailDTO from(LingxingProductClient.ProductDetail detail) {
        return new ProductDetailDTO(
                detail.id(), detail.cid(), detail.bid(), detail.sku(), detail.skuIdentifier(),
                detail.productName(), detail.picUrl(), detail.cgDelivery(), detail.cgTransportCosts(),
                detail.purchaseRemark(), detail.cgPrice(), detail.status(), detail.openStatus(),
                detail.isCombo(), detail.createTime(), detail.updateTime(), detail.productDeveloperUid(),
                detail.cgOptUid(), detail.cgOptUsername(), detail.spu(), detail.psId(), detail.attribute(),
                detail.brandName(), detail.categoryName(), detail.statusText(), detail.productDeveloper(),
                detail.supplierQuote(), detail.auxRelationList(), detail.customFields(), detail.globalTags(),
                detail.comboProductList().stream().map(ComboProductDTO::from).toList());
    }

    public record ComboProductDTO(Long productId, Long quantity, String sku) {

        private static ComboProductDTO from(LingxingProductClient.ComboProduct product) {
            return new ComboProductDTO(product.productId(), product.quantity(), product.sku());
        }
    }
}
