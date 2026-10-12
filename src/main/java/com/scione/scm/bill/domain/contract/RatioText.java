package com.scione.scm.bill.domain.contract;

import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 比例类字段（预付款比例 prepay_percent）的存储文本。
 * 库里保存 0～1 的比例值，统一保留两位小数：0 → "0.00"、0.3 或 "30%" → "0.30"。
 * 口径统一后，「合同当前值」与「领星最新值」的字符串比较才不会因为 0 / 0.00 这类写法差异报假差异。
 */
public final class RatioText {

    private static final int SCALE = 2;

    private RatioText() {
    }

    /** 数值入参：手动创建表单的 prepaymentRatio 就是 BigDecimal。 */
    public static String of(BigDecimal value) {
        if (value == null) return null;
        // 先检查原值，避免 1.001 四舍五入成 1.00 后绕过上限。
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "预付款比例必须在0到1之间（含0和1），例如0.3表示30%");
        }
        return value.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 文本入参：合同详情修改、领星供应商档案下发。兼容 "30%" 这类带百分号的写法；
     * 百分数先转为比例；非法数字拒绝保存，避免脏数据进入合同。
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
            boolean percent = trimmed.endsWith("%");
            BigDecimal ratio = new BigDecimal(percent ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed);
            return of(percent ? ratio.movePointLeft(2) : ratio);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "预付款比例格式不正确，请填写0到1之间的数字，例如0.3表示30%");
        }
    }
}
