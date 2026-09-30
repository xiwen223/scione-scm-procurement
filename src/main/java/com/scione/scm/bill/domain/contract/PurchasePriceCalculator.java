package com.scione.scm.bill.domain.contract;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 领星采购单价格换算；领星 tax_rate 使用百分数（例如 13.00 表示 13%）。 */
public final class PurchasePriceCalculator {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PurchasePriceCalculator() {
    }

    /** 含税订单反算不含税单价；税率缺失或非法时返回 null，避免把含税价误作不含税价。 */
    public static BigDecimal withoutTax(BigDecimal price, Integer isTax, String taxRate) {
        if (price == null || isTax == null) {
            return null;
        }
        if (isTax == 0) {
            return price.setScale(4, RoundingMode.HALF_UP);
        }
        if (isTax != 1 || taxRate == null || taxRate.isBlank()) {
            return null;
        }
        try {
            String normalized = taxRate.trim().replace("%", "").trim();
            BigDecimal percent = new BigDecimal(normalized);
            if (percent.signum() < 0) {
                return null;
            }
            BigDecimal divisor = BigDecimal.ONE.add(percent.divide(HUNDRED));
            return price.divide(divisor, 4, RoundingMode.HALF_UP);
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    /** 合同明细小计按“不含税单价 × 计划采购量”计算。 */
    public static BigDecimal lineAmount(BigDecimal unitPrice, Integer quantity) {
        return unitPrice == null || quantity == null ? null
                : unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }
}
