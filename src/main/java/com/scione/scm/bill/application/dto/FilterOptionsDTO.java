package com.scione.scm.bill.application.dto;

import lombok.Data;

import java.util.List;

/**
 * 采购交付预警看板筛选下拉选项。
 */
@Data
public class FilterOptionsDTO {

    private List<String> suppliers;
    private List<String> buyers;
    private List<String> warehouses;
}
