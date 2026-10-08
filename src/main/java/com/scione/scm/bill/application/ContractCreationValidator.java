package com.scione.scm.bill.application;

import com.scione.scm.bill.application.port.LingxingProductClient;
import com.scione.scm.bill.application.port.LingxingSupplierClient;
import com.scione.scm.bill.domain.company.BuyerCompany;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.posync.PoSyncRecord;
import com.scione.scm.bill.domain.posync.PoSyncRecordItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 创建采购合同前的统一必填校验，供自动和手动创建共用。
 *
 * <p>「商品图片是否缺失」这一项需要的商品图，校验阶段会**一次性批量查回**并随结果返回，
 * 交给创建阶段直接复用。原因：自动创建的流程是「先校验、再创建」，两边都要判断同一批
 * SKU 有没有图，若各查各的，一次建单会对领星商品接口重复调用 2N 次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContractCreationValidator {

    private final LingxingSupplierClient lingxingSupplierClient;
    private final LingxingProductClient lingxingProductClient;
    private final ContractRepository contractRepository;

    /**
     * 校验结果。
     *
     * @param missingFields        缺失/不满足的字段描述，空表示校验通过
     * @param picUrlsBySku         顺带批量查回的商品图片（sku → 图片 URL），供创建阶段复用
     * @param imageQueryFailedSkus 图片「查询失败」的 SKU，与「确实没有图」区分开便于排查
     */
    public record ValidationResult(List<String> missingFields,
                                   Map<String, String> picUrlsBySku,
                                   Set<String> imageQueryFailedSkus) {
    }

    /** 一次图片解析的产出：查到的图 + 查询失败的 SKU。 */
    private record ItemImages(Map<String, String> picUrlsBySku, Set<String> queryFailedSkus) {
    }

    public ValidationResult validate(PoSyncRecord po, BuyerCompany buyer) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(po.getPurchaseOrderNo())) missing.add("采购单号");
        if (!StringUtils.hasText(po.getSupplierName())) missing.add("供应商名称");
        if (!StringUtils.hasText(po.getContactPerson())) missing.add("供方联系人");
        if (!StringUtils.hasText(po.getSupplierPhone())) missing.add("供方电话");
        if (po.getAmountTotal() == null) missing.add("合同金额");
        if (buyer == null || !StringUtils.hasText(buyer.getCompanyName())) missing.add("需方");
        if (buyer == null || !StringUtils.hasText(buyer.getAddress())) missing.add("签订地点");

        validateSupplierProfile(po, missing);

        // 图片只在这里查一次：校验「有没有图」与创建「填图」共用这批结果
        ItemImages images = resolveItemImages(po.getItems());
        validateItems(po.getItems(), missing, images);
        return new ValidationResult(missing, images.picUrlsBySku(), images.queryFailedSkus());
    }

    private void validateSupplierProfile(PoSyncRecord po, List<String> missing) {
        if (po.getSupplierId() == null) {
            missing.add("供应商ID");
            return;
        }
        try {
            Optional<LingxingSupplierClient.SupplierProfile> profile = lingxingSupplierClient.findSupplierProfile(po.getSupplierId());
            if (profile.isEmpty()) {
                missing.add("供应商资料");
                return;
            }
            LingxingSupplierClient.SupplierProfile value = profile.get();
            if (!StringUtils.hasText(value.address())) missing.add("供方地址");
            if (!StringUtils.hasText(value.prepayPercent())) missing.add("预付款比例");
            if (!StringUtils.hasText(value.settlementMethod())) missing.add("结算方式");
            if (value.defaultPaymentAccount().isEmpty()) {
                missing.add("默认收款账户");
            }
        } catch (RuntimeException ex) {
            missing.add("供应商资料查询失败");
        }
    }

    private void validateItems(List<PoSyncRecordItem> items, List<String> missing, ItemImages images) {
        if (items == null || items.isEmpty()) {
            missing.add("采购商品明细");
            return;
        }
        boolean hasDeliveryDate = false;
        for (PoSyncRecordItem item : items) {
            String label = StringUtils.hasText(item.getSku()) ? "SKU=" + item.getSku() : "采购商品";
            if (!StringUtils.hasText(item.getSku())) missing.add(label + " SKU");
            if (!StringUtils.hasText(item.getProductName())) missing.add(label + " 品名及规格");
            if (item.getQuantityPlan() == null || item.getQuantityPlan() <= 0) missing.add(label + " 数量");
            if (item.getUnitPriceWithoutTax() == null || item.getUnitPriceWithoutTax().compareTo(BigDecimal.ZERO) < 0) missing.add(label + " 不含税单价");
            if (item.getExpectArriveTime() != null) hasDeliveryDate = true;
            validateImage(item, label, missing, images);
        }
        if (!hasDeliveryDate) missing.add("交付日期");
    }

    private void validateImage(PoSyncRecordItem item, String label, List<String> missing, ItemImages images) {
        if (!StringUtils.hasText(item.getSku())) return;
        if (images.queryFailedSkus().contains(item.getSku())) {
            missing.add(label + " 图片查询失败");
            return;
        }
        if (!StringUtils.hasText(images.picUrlsBySku().get(item.getSku()))) {
            missing.add(label + " 图片");
        }
    }

    /**
     * 批量解析明细商品图：先复用本地历史合同同 SKU 的图片（一次查询，命中就不必打扰领星），
     * 仍未命中的 SKU 再一次性批量查领星。
     *
     * <p>结果由 {@link #validate} 返回给调用方复用，避免创建阶段对同一批 SKU 再查一遍领星。</p>
     */
    private ItemImages resolveItemImages(List<PoSyncRecordItem> items) {
        if (items == null || items.isEmpty()) {
            return new ItemImages(Map.of(), Set.of());
        }
        Set<String> skus = items.stream()
                .map(PoSyncRecordItem::getSku)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (skus.isEmpty()) {
            return new ItemImages(Map.of(), Set.of());
        }

        Map<String, String> picUrlsBySku = new LinkedHashMap<>();
        // 1. 本地历史合同同 SKU 图片（一次查询）
        try {
            picUrlsBySku.putAll(contractRepository.findLatestItemPicUrlsBySkus(skus));
        } catch (RuntimeException ex) {
            log.warn("合同创建校验：本地商品图片缓存查询失败，改为直接查领星，待查 SKU 数={}", skus.size(), ex);
        }

        // 2. 仍未命中的 SKU 一次性批量查领星（接口本身支持 skus 数组）
        List<String> remains = skus.stream().filter(sku -> !picUrlsBySku.containsKey(sku)).toList();
        Set<String> queryFailedSkus = new LinkedHashSet<>();
        if (!remains.isEmpty()) {
            try {
                lingxingProductClient.findBySkus(remains).forEach((sku, product) -> {
                    if (product != null && StringUtils.hasText(product.picUrl())) {
                        picUrlsBySku.put(sku, product.picUrl());
                    }
                });
            } catch (RuntimeException ex) {
                log.warn("合同创建校验：批量查询领星商品图失败，待查 SKU 数={}", remains.size(), ex);
                queryFailedSkus.addAll(remains);
            }
        }
        return new ItemImages(picUrlsBySku, queryFailedSkus);
    }
}
