package com.scione.scm.bill.application.vo;

import lombok.Data;

import java.util.List;

@Data
public class PurchaseDeliveryFilterOptionsVO {
    private List<String> buyers;
    private List<String> suppliers;
    private List<String> warehouses;
}
