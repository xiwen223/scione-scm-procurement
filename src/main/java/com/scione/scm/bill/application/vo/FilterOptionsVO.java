package com.scione.scm.bill.application.vo;

import lombok.Data;

import java.util.List;

/**
 * 采购交付预警看板筛选下拉选项。
 */
@Data
public class FilterOptionsVO {

    private List<String> suppliers;
    private List<String> buyers;
    private List<String> warehouses;
}
