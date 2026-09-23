package com.scione.scm.bill.domain.contract;

import com.scione.scm.bill.domain.company.BuyerCompany;
import com.scione.scm.bill.domain.posync.PoSyncRecord;
import com.scione.scm.bill.domain.posync.PoSyncRecordItem;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 采购合同聚合根（对应 contract 主表）。
 * 领域层零技术依赖：由应用层用 createFromPo 从 PO 自动建单，由持久化适配器用 rehydrate 重建。
 */
@Getter
@Setter
public class Contract {
    public static final int TYPE_PURCHASE = 1;
    public static final int SOURCE_LINGXING = 1;
    public static final int CREATE_TYPE_AUTO = 1;
    public static final String SYSTEM_OPERATOR = "system";

    @Setter
    private Long id;
    private String contractNo;
    private String contractName;
    private Integer contractType;
    private String purchaseOrderNo;
    private Integer sourceType;
    private Long supplierId;
    private String supplierName;
    private String supplierPhone;
    private String supplierAddress;
    private String contactPerson;
    private Long buyerCompanyId;
    private String buyerCompanyName;
    private String buyerCompanyCode;
    private String buyerAddress;
    private String postCode;
    private String buyerPhone;
    private String fax;
    private BigDecimal originalAmount;
    private BigDecimal discountedAmount;
    private BigDecimal contractAmount;
    private LocalDate contractDate;
    private LocalDate deliveryDate;       // 交货日期
    private ContractStatus status;
    private String creatorId;
    private String creatorName;
    private Integer createType;
    private final List<ContractItem> items = new ArrayList<>();
    private String contractPdfUrl;    // 原始合同 PDF URL
    private String signedPdfUrl;      // 已签署合同 PDF URL

    private Contract() {
    }

    /**
     * 从领星采购单自动创建合同（含明细）。
     *
     * @param po         已落库的 PO 单头（含明细）
     * @param buyer      默认需方公司
     * @param contractNo 已生成并去重的合同编号
     */
    public static Contract createFromPo(PoSyncRecord po, BuyerCompany buyer, String contractNo) {
        Contract c = new Contract();
        c.contractNo = contractNo;
        c.contractName = "采购合同-" + po.getSupplierName() + "-" + po.getPurchaseOrderNo();
        c.contractType = TYPE_PURCHASE;
        c.purchaseOrderNo = po.getPurchaseOrderNo();
        c.sourceType = SOURCE_LINGXING;
        c.supplierId = po.getSupplierId();
        c.supplierName = po.getSupplierName();
        c.supplierPhone = po.getSupplierPhone();
        c.supplierAddress = null;  // 领星没有供应商地址，保持为 null
        c.contactPerson = po.getContactPerson();
        c.buyerCompanyId = buyer.getId();
        c.buyerCompanyName = buyer.getCompanyName();
        c.buyerCompanyCode = buyer.getCreditCode();
        c.buyerAddress = buyer.getAddress();
        c.postCode = buyer.getPostCode();
        c.buyerPhone = buyer.getPhone();
        c.fax = buyer.getFax();
        c.originalAmount = po.getAmountTotal();
        c.discountedAmount = null;
        c.contractAmount = po.getAmountTotal();
        c.contractDate = LocalDate.now();
        // 自动创建时，从明细中取最早的交货日期
        c.deliveryDate = po.getItems() != null ? po.getItems().stream()
                .filter(item -> item.getExpectArriveTime() != null)
                .map(PoSyncRecordItem::getExpectArriveTime)
                .min(LocalDate::compareTo)
                .orElse(null) : null;
        c.status = ContractStatus.CREATED;
        c.creatorId = SYSTEM_OPERATOR;
        c.creatorName = SYSTEM_OPERATOR;
        c.createType = CREATE_TYPE_AUTO;

        List<PoSyncRecordItem> poItems = po.getItems();
        if (poItems != null) {
            for (PoSyncRecordItem it : poItems) {
                c.addItem(toItem(contractNo, it));
            }
        }
        return c;
    }

    /**
     * 由持久化适配器重建聚合。
     */
    public static Contract rehydrate(Long id, String contractNo, String contractName, Integer contractType,
                                     String purchaseOrderNo, Integer sourceType, Long supplierId, String supplierName,
                                     String supplierPhone, String supplierAddress, String contactPerson,
                                     Long buyerCompanyId, String buyerCompanyName,
                                     String buyerCompanyCode, String buyerAddress, String postCode,
                                     String buyerPhone, String fax,
                                     BigDecimal originalAmount, BigDecimal discountedAmount,
                                     BigDecimal contractAmount, LocalDate contractDate, LocalDate deliveryDate, Integer status,
                                     String creatorId, String creatorName, Integer createType,
                                     String contractPdfUrl, String signedPdfUrl,
                                     List<ContractItem> items) {
        Contract c = new Contract();
        c.id = id;
        c.contractNo = contractNo;
        c.contractName = contractName;
        c.contractType = contractType;
        c.purchaseOrderNo = purchaseOrderNo;
        c.sourceType = sourceType;
        c.supplierId = supplierId;
        c.supplierName = supplierName;
        c.supplierPhone = supplierPhone;
        c.supplierAddress = supplierAddress;
        c.contactPerson = contactPerson;
        c.buyerCompanyId = buyerCompanyId;
        c.buyerCompanyName = buyerCompanyName;
        c.buyerCompanyCode = buyerCompanyCode;
        c.buyerAddress = buyerAddress;
        c.postCode = postCode;
        c.buyerPhone = buyerPhone;
        c.fax = fax;
        c.originalAmount = originalAmount;
        c.discountedAmount = discountedAmount;
        c.contractAmount = contractAmount;
        c.contractDate = contractDate;
        c.deliveryDate = deliveryDate;
        c.status = status == null ? ContractStatus.CREATED : ContractStatus.of(status);
        c.creatorId = creatorId;
        c.creatorName = creatorName;
        c.createType = createType;
        c.contractPdfUrl = contractPdfUrl;
        c.signedPdfUrl = signedPdfUrl;
        if (items != null) {
            c.items.addAll(items);
        }
        return c;
    }

    public void addItem(ContractItem item) {
        items.add(item);
    }

    private static ContractItem toItem(String contractNo, PoSyncRecordItem src) {
        ContractItem item = new ContractItem();
        item.setContractNo(contractNo);
        item.setSku(src.getSku());
        item.setProductId(src.getProductId());
        item.setProductName(src.getProductName());
        item.setSpecification(src.getModel());
        item.setQuantity(src.getQuantityPlan());
        item.setUnit(null);
        item.setUnitPrice(src.getUnitPrice());
        item.setAmount(src.getAmount());
        item.setDeliveryDate(src.getExpectArriveTime());
        item.setWarehouseName(src.getWarehouseName());
        item.setRemark(src.getRemark());
        item.setCasesNum(src.getCasesNum());
        item.setQuantityPerCase(src.getQuantityPerCase());
        item.setPicUrl(src.getPicUrl());
        return item;
    }
}