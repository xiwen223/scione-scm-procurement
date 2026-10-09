package com.scione.scm.bill.application;

import com.scione.scm.bill.application.dto.ContractLingxingSyncDTO;
import com.scione.scm.bill.application.port.LingxingPurchaseOrderClient;
import com.scione.scm.bill.application.port.LingxingSupplierClient;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.contract.ContractStatus;
import com.scione.scm.bill.domain.contract.PurchasePriceCalculator;
import com.scione.scm.bill.domain.contract.RatioText;
import com.scione.scm.bill.domain.posync.PoSyncRecord;
import com.scione.scm.bill.domain.posync.PoSyncRecordItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 领星数据同步不做全量覆盖：先返回逐字段差异，再仅应用用户勾选的字段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractLingxingSyncService {
    private static final Duration COMPARE_CACHE_TTL = Duration.ofMinutes(10);

    private final ContractRepository contractRepository;
    private final LingxingPurchaseOrderClient lingxingPurchaseOrderClient;
    private final LingxingSupplierClient lingxingSupplierClient;
    /** 比对页展示的快照，应用时直接使用，避免再次调用领星。 */
    private final Map<Long, CachedComparison> comparisonCache = new ConcurrentHashMap<>();

    public ContractLingxingSyncDTO.CompareResponse compare(Long contractId) {
        Snapshot snapshot = loadSnapshot(contractId);
        List<SyncField> fields = buildFields(snapshot.contract(), snapshot.po(), snapshot.supplier());
        comparisonCache.put(contractId, new CachedComparison(fields, LocalDateTime.now()));
        return new ContractLingxingSyncDTO.CompareResponse(
                snapshot.contract().getId(), snapshot.contract().getContractNo(), snapshot.queryTime(),
                fields.stream().map(SyncField::toDifference).toList());
    }

    /** 用户查看差异后选择保留合同当前值并继续签署时，保存完整差异审计日志。 */
    @Transactional
    public void recordProceedWithoutSync(Long contractId, String operatorEmail) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同不存在"));
        CachedComparison cached = comparisonCache.get(contractId);
        if (cached == null || cached.createdAt().plus(COMPARE_CACHE_TTL).isBefore(LocalDateTime.now())) {
            comparisonCache.remove(contractId);
            throw new BusinessException(ResultCode.PARAM_ERROR, "领星比对结果已过期，请重新点击同步领星数据后再签署");
        }
        String details = cached.fields().stream()
                .map(this::describe)
                .reduce("用户未同步领星字段，确认仍按当前合同内容发起签署", (left, right) -> left + "; " + right);
        String operator = StringUtils.hasText(operatorEmail) ? operatorEmail : Contract.SYSTEM_OPERATOR;
        contractRepository.saveOperationLog(ContractOperationLog.ofUpdate(
                contract.getId(), contract.getContractNo(), operator, operator,
                "签署前领星差异确认", details));
        comparisonCache.remove(contractId);
        log.info("用户确认领星差异后继续签署：contractNo={}, differenceCount={}",
                contract.getContractNo(), cached.fields().size());
    }

    /**
     * 签署前复核：用**与「同步领星数据」弹窗完全相同**的字段口径生成差异文本（含商品明细全部字段）。
     *
     * <p>这里的取值全部来自领星实时接口，价格一律按「含税单价 ÷ (1 + 税率)」折成不含税后再比，
     * 因为领星只返回含税单价、而合同明细存的是不含税单价；直接比原始价会把每一行都报成差异。
     * 也不要用本地 po_sync_record_item 的快照：它可能是价格口径升级前的旧数据（unit_price_without_tax 为空）。</p>
     *
     * <p>领星查不到采购单时只回一行提示而不抛异常 —— 是否阻断由签署流程自己决定。</p>
     */
    public List<String> describeDifferences(Contract contract, LingxingSupplierClient.SupplierProfile supplier) {
        LingxingPurchaseOrderClient.PurchaseOrderData remotePo;
        try {
            remotePo = lingxingPurchaseOrderClient.findByOrderNo(contract.getPurchaseOrderNo()).orElse(null);
        } catch (RuntimeException exception) {
            // 该方法只服务于「签署前复核」：领星抖动（超时 / 限流 / IP 白名单）时返回一行说明，
            // 不能把异常抛给签署流程 —— 用户已经确认过差异，不该因为第二次实时查询失败而签不了。
            log.warn("签署前领星复核查询采购单失败，跳过逐字段比对：contractNo={}, purchaseOrderNo={}, reason={}",
                    contract.getContractNo(), contract.getPurchaseOrderNo(), exception.getMessage());
            return List.of("领星采购单：本次查询失败（网络超时或接口限流），已跳过逐字段比对");
        }
        if (remotePo == null) {
            return List.of("领星采购单：未查询到");
        }
        PoSyncRecord po = toPoSnapshot(remotePo);
        List<String> lines = new ArrayList<>(buildFields(contract, po, supplier).stream().map(this::describe).toList());
        lines.addAll(describeSkuOnlyOnOneSide(contract, po));
        return lines;
    }

    /**
     * 只在一边存在的商品：buildFields 只比「两边都有的 SKU」，单向存在的会被跳过。
     * 这类差异**没法同步**（同步只能改字段，不能增删明细），但签署前必须让人看见——
     * 合同多出来的商品会被盖章，领星多出来的商品则永远进不了合同。
     */
    private List<String> describeSkuOnlyOnOneSide(Contract contract, PoSyncRecord po) {
        Set<String> poSkus = new LinkedHashSet<>();
        for (PoSyncRecordItem item : po.getItems()) {
            if (StringUtils.hasText(item.getSku())) {
                poSkus.add(item.getSku());
            }
        }
        Set<String> contractSkus = new LinkedHashSet<>();
        for (ContractItem item : contract.getItems()) {
            if (StringUtils.hasText(item.getSku())) {
                contractSkus.add(item.getSku());
            }
        }
        List<String> lines = new ArrayList<>();
        for (String sku : contractSkus) {
            if (!poSkus.contains(sku)) {
                lines.add("商品SKU=" + sku + "：合同存在，领星采购单不存在");
            }
        }
        for (String sku : poSkus) {
            if (!contractSkus.contains(sku)) {
                lines.add("商品SKU=" + sku + "：领星采购单存在，合同未包含");
            }
        }
        return lines;
    }

    /** 差异行的统一文本：与弹窗里「字段 / 合同当前值 / 领星最新值」三列同源。 */
    private String describe(SyncField field) {
        return field.label() + "：合同[" + field.currentValue() + "]，领星[" + field.lingxingValue() + "]";
    }

    @Transactional
    public ContractLingxingSyncDTO.ApplyResponse apply(Long contractId,
                                                        ContractLingxingSyncDTO.ApplyRequest request,
                                                        String operatorEmail) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同不存在"));
        if (contract.isSignLaunching() || contract.getStatus() != ContractStatus.CREATED) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅创建状态的合同可同步领星数据");
        }
        Set<String> selectedKeys = request == null || request.selectedFieldKeys() == null
                ? Set.of() : new LinkedHashSet<>(request.selectedFieldKeys());
        if (selectedKeys.isEmpty()) {
            return new ContractLingxingSyncDTO.ApplyResponse(contractId, contract.getContractNo(), 0, List.of());
        }

        CachedComparison cached = comparisonCache.get(contractId);
        if (cached == null || cached.createdAt().plus(COMPARE_CACHE_TTL).isBefore(LocalDateTime.now())) {
            comparisonCache.remove(contractId);
            throw new BusinessException(ResultCode.PARAM_ERROR, "领星比对结果已过期，请重新点击同步领星数据后再应用");
        }
        List<SyncField> selectedFields = cached.fields().stream()
                .filter(field -> selectedKeys.contains(field.key()))
                .toList();
        if (selectedFields.isEmpty()) {
            return new ContractLingxingSyncDTO.ApplyResponse(contractId, contract.getContractNo(), 0, List.of());
        }

        List<String> details = new ArrayList<>();
        for (SyncField field : selectedFields) {
            field.apply().accept(contract);
            details.add(field.label() + "：" + field.currentValue() + " → " + field.lingxingValue());
        }
        recalculateAmounts(contract);
        contractRepository.update(contract);

        String operator = StringUtils.hasText(operatorEmail) ? operatorEmail : Contract.SYSTEM_OPERATOR;
        contractRepository.saveOperationLog(ContractOperationLog.ofUpdate(
                contract.getId(), contract.getContractNo(), operator, operator,
                "同步领星数据", String.join("；", details)));
        comparisonCache.remove(contractId);
        log.info("用户选择同步领星字段完成：contractNo={}, selectedCount={}, keys={}",
                contract.getContractNo(), selectedFields.size(), selectedFields.stream().map(SyncField::key).toList());
        return new ContractLingxingSyncDTO.ApplyResponse(contract.getId(), contract.getContractNo(),
                selectedFields.size(), selectedFields.stream().map(SyncField::key).toList());
    }

    private Snapshot loadSnapshot(Long contractId) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同不存在"));
        LingxingPurchaseOrderClient.PurchaseOrderData remotePo = lingxingPurchaseOrderClient
                .findByOrderNo(contract.getPurchaseOrderNo())
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "领星未查询到该采购单：" + contract.getPurchaseOrderNo()));
        PoSyncRecord po = toPoSnapshot(remotePo);
        LingxingSupplierClient.SupplierProfile supplier = contract.getSupplierId() == null ? null
                : lingxingSupplierClient.findSupplierProfile(contract.getSupplierId()).orElse(null);
        return new Snapshot(contract, po, supplier, LocalDateTime.now());
    }

    private PoSyncRecord toPoSnapshot(LingxingPurchaseOrderClient.PurchaseOrderData source) {
        PoSyncRecord po = new PoSyncRecord();
        po.setPurchaseOrderNo(source.orderSn());
        po.setSupplierId(source.supplierId());
        po.setSupplierName(source.supplierName());
        po.setSupplierPhone(source.contactNumber());
        po.setContactPerson(source.contactPerson());
        po.setPoStatus(source.status());
        po.setPoStatusText(source.statusText());
        po.setAmountTotal(source.amountTotal());
        po.setTotalPrice(source.totalPrice());
        po.setWarehouseName(source.warehouseName());
        po.setRemark(source.remark());
        po.setPoUpdateTime(source.updateTime());
        for (LingxingPurchaseOrderClient.PurchaseOrderItemData sourceItem : source.items()) {
            PoSyncRecordItem item = new PoSyncRecordItem();
            item.setProductId(sourceItem.productId());
            item.setProductName(sourceItem.productName());
            item.setSku(sourceItem.sku());
            item.setModel(sourceItem.model());
            BigDecimal unitPrice = PurchasePriceCalculator.withoutTax(
                    sourceItem.price(), source.isTax(), sourceItem.taxRate());
            if (sourceItem.price() != null && unitPrice == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "领星采购商品[" + sourceItem.sku() + "]缺少有效税率，无法计算不含税单价");
            }
            item.setUnitPrice(sourceItem.price());
            item.setUnitPriceWithoutTax(unitPrice);
            item.setAmount(sourceItem.amount());
            item.setQuantityPlan(sourceItem.quantityPlan());
            // 采购量与收货量必须一起带下来：合同明细的数量/金额是「不含税单价 × 实际采购量(quantity_real)」
            // （见 Contract#toItem），这里漏设会让金额与数量在比对时算成 null，
            // 表现就是「合同有值、领星最新值为空」的假差异，同步后还会把合同金额清空。
            item.setQuantityReal(sourceItem.quantityReal());
            item.setQuantityReceive(sourceItem.quantityReceive());
            item.setWarehouseName(sourceItem.warehouseName());
            item.setExpectArriveTime(sourceItem.expectArriveTime());
            item.setRemark(sourceItem.remark());
            item.setPicUrl(sourceItem.picUrl());
            po.getItems().add(item);
        }
        return po;
    }

    private List<SyncField> buildFields(Contract contract, PoSyncRecord po,
                                        LingxingSupplierClient.SupplierProfile supplier) {
        List<SyncField> fields = new ArrayList<>();
        add(fields, "contract.supplierName", "供方名称", "供应商", contract.getSupplierName(), po.getSupplierName(), false,
                value -> value.setSupplierName(po.getSupplierName()));
        add(fields, "contract.supplierPhone", "供方电话", "供应商", contract.getSupplierPhone(), po.getSupplierPhone(), false,
                value -> value.setSupplierPhone(po.getSupplierPhone()));
        add(fields, "contract.contactPerson", "供方联系人", "供应商", contract.getContactPerson(), po.getContactPerson(), false,
                value -> value.setContactPerson(po.getContactPerson()));

        LocalDate poDeliveryDate = po.getItems().stream().map(PoSyncRecordItem::getExpectArriveTime)
                .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        add(fields, "contract.deliveryDate", "交货日期", "采购单", contract.getDeliveryDate(), poDeliveryDate, true,
                value -> value.setDeliveryDate(poDeliveryDate));

        if (supplier != null) {
            add(fields, "contract.supplierAddress", "供方地址", "供应商", contract.getSupplierAddress(), supplier.address(), false,
                    value -> value.setSupplierAddress(supplier.address()));
            add(fields, "contract.supplierCreditCode", "供方统一社会信用代码", "供应商", contract.getSupplierCreditCode(), supplier.creditCode(), false,
                    value -> value.setSupplierCreditCode(supplier.creditCode()));
            // 预付款比例两边都按「两位小数」规范化后再比：合同存 "0.00"、领星下发 "0" 时是同一个值，
            // 否则会报一条假差异，用户一点同步就把合同的比例改成另一种写法。
            add(fields, "contract.prepayPercent", "预付款比例", "供应商",
                    RatioText.of(contract.getPrepayPercent()), RatioText.of(supplier.prepayPercent()), false,
                    value -> value.setPrepayPercent(RatioText.of(supplier.prepayPercent())));
            add(fields, "contract.settlementMethod", "结算方式", "供应商", contract.getSettlementMethod(), supplier.settlementMethod(), false,
                    value -> value.setSettlementMethod(supplier.settlementMethod()));
            supplier.defaultPaymentAccount().ifPresent(account -> {
                add(fields, "contract.supplierAccountName", "默认收款账户名称", "默认收款账户", contract.getSupplierAccountName(), account.accountName(), false,
                        value -> value.setSupplierAccountName(account.accountName()));
                add(fields, "contract.supplierBankAccount", "默认收款银行账号", "默认收款账户", contract.getSupplierBankAccount(), account.accountId(), false,
                        value -> value.setSupplierBankAccount(account.accountId()));
                add(fields, "contract.supplierBankName", "默认收款开户行", "默认收款账户", contract.getSupplierBankName(), account.bankName(), false,
                        value -> value.setSupplierBankName(account.bankName()));
            });
        }

        Map<String, PoSyncRecordItem> poItems = new LinkedHashMap<>();
        for (PoSyncRecordItem item : po.getItems()) {
            if (StringUtils.hasText(item.getSku())) poItems.put(item.getSku(), item);
        }
        for (ContractItem item : contract.getItems()) {
            PoSyncRecordItem poItem = poItems.get(item.getSku());
            if (poItem == null) continue;
            String prefix = "商品[" + item.getSku() + "]";
            String keyPrefix = "item." + item.getId() + ".";
            add(fields, keyPrefix + "productName", prefix + "品名", "采购商品", item.getProductName(), poItem.getProductName(), true,
                    value -> findItem(value, item.getId()).setProductName(poItem.getProductName()));
            add(fields, keyPrefix + "specification", prefix + "规格", "采购商品", item.getSpecification(), poItem.getModel(), true,
                    value -> findItem(value, item.getId()).setSpecification(poItem.getModel()));
            // 数量与金额统一按领星「实际采购量」(quantity_real) 口径，与 Contract#toItem 建合同时的算法一致；
            // 领星还没回填实际采购量时退回计划采购量，否则两个字段都算不出值，会生成一条没法同步的空差异。
            Integer lingxingQuantity = PurchasePriceCalculator.effectiveQuantity(
                    poItem.getQuantityReal(), poItem.getQuantityPlan());
            // 领星侧算不出值时整行不展示：数量/金额是**默认勾选**的，如果领星值为空还列成差异，
            // 用户一点同步就会把合同里已有的数量/金额清成 null。
            if (lingxingQuantity != null) {
                add(fields, keyPrefix + "quantity", prefix + "数量", "采购商品", item.getQuantity(), lingxingQuantity, true,
                        value -> findItem(value, item.getId()).setQuantity(lingxingQuantity));
            }
            add(fields, keyPrefix + "unitPrice", prefix + "不含税单价", "采购商品", item.getUnitPrice(), poItem.getUnitPriceWithoutTax(), true,
                    value -> findItem(value, item.getId()).setUnitPrice(poItem.getUnitPriceWithoutTax()));
            BigDecimal amountWithoutTax = PurchasePriceCalculator.lineAmount(
                    poItem.getUnitPriceWithoutTax(), lingxingQuantity);
            if (amountWithoutTax != null) {
                add(fields, keyPrefix + "amount", prefix + "金额", "采购商品", item.getAmount(), amountWithoutTax, true,
                        value -> findItem(value, item.getId()).setAmount(amountWithoutTax));
            }
            add(fields, keyPrefix + "deliveryDate", prefix + "交货日期", "采购商品", item.getDeliveryDate(), poItem.getExpectArriveTime(), true,
                    value -> findItem(value, item.getId()).setDeliveryDate(poItem.getExpectArriveTime()));
            add(fields, keyPrefix + "warehouseName", prefix + "仓库", "采购商品", item.getWarehouseName(), poItem.getWarehouseName(), true,
                    value -> findItem(value, item.getId()).setWarehouseName(poItem.getWarehouseName()));
            add(fields, keyPrefix + "remark", prefix + "备注", "采购商品", item.getRemark(), poItem.getRemark(), true,
                    value -> findItem(value, item.getId()).setRemark(poItem.getRemark()));
            // 图片 URL 直接来自同一次采购单同步结果；不在用户点击同步时逐 SKU 请求商品接口。
            if (StringUtils.hasText(poItem.getPicUrl())) {
                add(fields, keyPrefix + "picUrl", prefix + "图片", "采购商品", item.getPicUrl(), poItem.getPicUrl(), true,
                        value -> findItem(value, item.getId()).setPicUrl(poItem.getPicUrl()));
            }
        }
        return fields;
    }

    private ContractItem findItem(Contract contract, Long itemId) {
        return contract.getItems().stream().filter(item -> itemId.equals(item.getId())).findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同明细不存在：" + itemId));
    }

    private void add(List<SyncField> fields, String key, String label, String category,
                     Object current, Object lingxing, boolean defaultSelected, Consumer<Contract> apply) {
        String currentValue = valueOf(current);
        String lingxingValue = valueOf(lingxing);
        if (!currentValue.equals(lingxingValue)) {
            fields.add(new SyncField(key, label, category, currentValue, lingxingValue, defaultSelected, apply));
        }
    }

    private void recalculateAmounts(Contract contract) {
        BigDecimal original = contract.getItems().stream().map(ContractItem::getAmount)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        contract.setOriginalAmount(original);
        BigDecimal discount = contract.getDiscountedAmount() == null ? BigDecimal.ZERO : contract.getDiscountedAmount();
        if (discount.compareTo(original) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "同步后的原价小于当前折扣，请先调整折扣后再同步");
        }
        contract.setContractAmount(original.subtract(discount));
    }

    private String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record Snapshot(Contract contract, PoSyncRecord po, LingxingSupplierClient.SupplierProfile supplier,
                            LocalDateTime queryTime) {
    }

    private record CachedComparison(List<SyncField> fields, LocalDateTime createdAt) {
    }

    private record SyncField(String key, String label, String category, String currentValue,
                             String lingxingValue, boolean defaultSelected, Consumer<Contract> apply) {
        ContractLingxingSyncDTO.Difference toDifference() {
            return new ContractLingxingSyncDTO.Difference(key, label, category, currentValue, lingxingValue, defaultSelected);
        }
    }
}
