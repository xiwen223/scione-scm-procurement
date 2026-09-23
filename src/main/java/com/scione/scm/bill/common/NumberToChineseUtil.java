package com.scione.scm.bill.common;

import java.math.BigDecimal;

/**
 * 数字转中文大写（金额专用）。
 */
public class NumberToChineseUtil {

    private static final String[] DIGITS = {"零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖"};
    private static final String[] UNITS = {"", "拾", "佰", "仟"};
    private static final String[] BIG_UNITS = {"", "万", "亿"};

    public static String convert(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return "零元整";
        }

        long yuan = amount.longValue();
        int jiao = amount.multiply(BigDecimal.TEN).remainder(BigDecimal.TEN).intValue();
        int fen = amount.multiply(new BigDecimal("100")).remainder(BigDecimal.TEN).intValue();

        StringBuilder result = new StringBuilder();
        if (yuan > 0) {
            result.append(convertInteger(yuan)).append("元");
        } else {
            result.append("零元");
        }

        // 如果角和分都为0，直接返回"整"
        if (jiao == 0 && fen == 0) {
            result.append("整");
            return result.toString();
        }

        // 处理角位
        if (jiao > 0) {
            result.append(DIGITS[jiao]).append("角");
        } else if (fen > 0) {
            result.append("零");
        }

        // 处理分位
        if (fen > 0) {
            result.append(DIGITS[fen]).append("分");
        }

        return result.toString();
    }

    private static String convertInteger(long num) {
        if (num == 0) return DIGITS[0];

        StringBuilder result = new StringBuilder();
        int unitIndex = 0;

        while (num > 0) {
            int section = (int) (num % 10000);
            if (section > 0) {
                String sectionStr = convertSection(section);
                result.insert(0, sectionStr + BIG_UNITS[unitIndex]);
            } else if (result.length() > 0) {
                result.insert(0, DIGITS[0]);
            }
            num /= 10000;
            unitIndex++;
        }

        return result.toString().replaceAll("零+", "零").replaceAll("零([万亿])", "$1").replaceAll("零$", "");
    }

    private static String convertSection(int section) {
        StringBuilder result = new StringBuilder();
        int unitIndex = 0;
        boolean needZero = false;

        while (section > 0) {
            int digit = section % 10;
            if (digit == 0) {
                needZero = true;
            } else {
                if (needZero && result.length() > 0) {
                    result.insert(0, DIGITS[0]);
                }
                result.insert(0, DIGITS[digit] + UNITS[unitIndex]);
                needZero = false;
            }
            section /= 10;
            unitIndex++;
        }

        return result.toString();
    }
}