package com.scione.scm.bill.infrastructure.pdf;

import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenPdfContractPdfGeneratorTest {

    @Test
    void generatesPdfWithoutOfficeRuntime() {
        ContractItem item = new ContractItem();
        item.setSku("SKU-001");
        item.setProductName("测试商品");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("50.00"));
        item.setAmount(new BigDecimal("100.00"));
        item.setDeliveryDate(LocalDate.of(2026, 10, 15));

        Contract contract = Contract.rehydrate(1L, "HTTEST001", "采购合同-测试", 1,
                "POTEST001", 1, 1L, "测试供方", "13800138000", "913100001234567890",
                "测试收款人", "测试银行", "6222021000000000000", "0", "交付即结",
                "测试供方地址", "张三", 1L, "测试需方", "913100009999999999",
                "测试需方地址", "200000", "021-12345678", "021-87654321",
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                LocalDate.of(2026, 9, 27), LocalDate.of(2026, 10, 15), 1,
                "test", "test", 1, null, null, null, List.of(item));

        byte[] pdf = new OpenPdfContractPdfGenerator().generate(contract);

        assertTrue(pdf.length > 1000);
        assertEquals('%', pdf[0]);
        assertEquals('P', pdf[1]);
        assertEquals('D', pdf[2]);
        assertEquals('F', pdf[3]);
    }
}
