package com.scione.scm.bill.application.dto;

import lombok.Data;

import java.util.List;

@Data
public class PurchaseDeliveryFilterOptionsDTO {
    private List<String> buyers;
    private List<String> suppliers;
    private List<String> warehouses;
}
