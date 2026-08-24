package com.scione.scm.bill.domain.shippingmark;

import com.scione.scm.bill.domain.shippingmark.enums.MarkStatus;

import java.util.List;

/**
 * 箱唛主单列表筛选条件。
 */
public record ShippingMarkQuery(
        String billNo,
        String billName,
        String creator,
        List<MarkStatus> statuses,
        int pageNum,
        int pageSize) {

    public int offset() {
        return (pageNum - 1) * pageSize;
    }
}
