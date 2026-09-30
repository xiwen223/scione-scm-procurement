package com.scione.scm.bill.application;

import com.scione.scm.bill.application.port.LingxingProductClient;
import com.scione.scm.bill.application.port.LingxingSupplierClient;
import com.scione.scm.bill.domain.company.BuyerCompany;
import com.scione.scm.bill.domain.posync.PoSyncRecord;
import com.scione.scm.bill.domain.posync.PoSyncRecordItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 创建采购合同前的统一必填校验，供自动和手动创建共用。 */
@Component
@RequiredArgsConstructor
public class ContractCreationValidator {

    private final LingxingSupplierClient lingxingSupplierClient;
    private final LingxingProductClient lingxingProductClient;

    public List<String> validate(PoSyncRecord po, BuyerCompany buyer) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(po.getPurchaseOrderNo())) missing.add("采购单号");
        if (!StringUtils.hasText(po.getSupplierName())) missing.add("供应商名称");
        if (!StringUtils.hasText(po.getContactPerson())) missing.add("供方联系人");
        if (!StringUtils.hasText(po.getSupplierPhone())) missing.add("供方电话");
        if (po.getAmountTotal() == null) missing.add("合同金额");
        if (buyer == null || !StringUtils.hasText(buyer.getCompanyName())) missing.add("需方");
        if (buyer == null || !StringUtils.hasText(buyer.getAddress())) missing.add("签订地点");

        validateSupplierProfile(po, missing);
        validateItems(po.getItems(), missing);
        return missing;
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

    private void validateItems(List<PoSyncRecordItem> items, List<String> missing) {
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
            validateImage(item, label, missing);
        }
        if (!hasDeliveryDate) missing.add("交付日期");
    }

    private void validateImage(PoSyncRecordItem item, String label, List<String> missing) {
        if (!StringUtils.hasText(item.getSku())) return;
        try {
            boolean hasImage = lingxingProductClient.findBySku(item.getSku())
                    .map(LingxingProductClient.ProductDetail::picUrl)
                    .filter(StringUtils::hasText)
                    .isPresent();
            if (!hasImage) missing.add(label + " 图片");
        } catch (RuntimeException ex) {
            missing.add(label + " 图片查询失败");
        }
    }
}
