package com.scione.scm.bill.domain.contract;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 比例类字段（预付款比例 prepay_percent）的存储文本。
 * 库里是字符串列，写入前统一保留两位小数：填 0 → "0.00"、0.3 → "0.30"、领星下发 "30%" → "30.00"。
 * 口径统一后，「合同当前值」与「领星最新值」的字符串比较才不会因为 0 / 0.00 这类写法差异报假差异。
 */
public final class RatioText {

    private static final int SCALE = 2;

    private RatioText() {
    }

    /** 数值入参：手动创建表单的 prepaymentRatio 就是 BigDecimal。 */
    public static String of(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 文本入参：合同详情修改、领星供应商档案下发。兼容 "30%" 这类带百分号的写法；
     * 解析不出数字时原样返回（去空白后），避免把脏数据写坏成 null。
     */
    public static String of(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        try {
            return of(new BigDecimal(trimmed.replace("%", "").trim()));
        } catch (NumberFormatException exception) {
            return trimmed;
        }
    }
}
