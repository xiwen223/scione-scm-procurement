package com.scione.scm.bill.domain.shippingmark;

import java.util.List;

/**
 * 箱唛主单分页查询结果。
 */
public record ShippingMarkPage(long total, List<ShippingMark> records) {
}
