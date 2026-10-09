package com.scione.scm.bill.application;

import com.scione.scm.bill.application.dto.ContractCreateRequest;
import com.scione.scm.bill.application.dto.ContractCreateResponse;
import com.scione.scm.bill.application.port.ContractFileStore;
import com.scione.scm.bill.application.port.ContractPdfConverter;
import com.scione.scm.bill.application.port.ContractTemplateService;
import com.scione.scm.bill.application.port.LingxingProductClient;
import com.scione.scm.bill.application.port.LingxingPurchaseOrderClient;
import com.scione.scm.bill.application.port.LingxingSupplierClient;
import com.scione.scm.bill.config.ContractSupplierWhitelistProperties;
import com.scione.scm.bill.domain.company.BuyerCompany;
import com.scione.scm.bill.domain.company.BuyerCompanyRepository;
import com.scione.scm.bill.domain.contract.ActiveContractRef;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.contract.PurchasePriceCalculator;
import com.scione.scm.bill.domain.contract.RatioText;
import com.scione.scm.bill.domain.posync.PoSyncRecord;
import com.scione.scm.bill.domain.posync.PoSyncRecordItem;
import com.scione.scm.bill.domain.posync.PoSyncRepository;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 合同自动创建应用服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractAutoCreateService {

    private final PoSyncRepository poSyncRepository;
    private final BuyerCompanyRepository buyerCompanyRepository;
    private final ContractRepository contractRepository;
    private final ContractTemplateService contractTemplateService;
    private final ContractFileStore contractFileStore;
    private final ContractPdfConverter contractPdfConverter;
    private final LingxingProductClient lingxingProductClient;
    private final LingxingPurchaseOrderClient lingxingPurchaseOrderClient;
    private final ContractSupplierWhitelistProperties supplierWhitelist;
    private final ContractCreationValidator contractCreationValidator;
    private final LingxingSupplierClient lingxingSupplierClient;
    private final ContractCreateProgressTracker contractCreateProgressTracker;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** 创建前供页面提示使用；只查询状态，不创建合同。 */
    public ManualPoStatus checkManualPoStatus(String purchaseOrderNo) {
        if (!StringUtils.hasText(purchaseOrderNo)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "采购单号不能为空");
        }
        String orderNo = purchaseOrderNo.trim();
        Optional<PoSyncRecord> local = poSyncRepository.findByPurchaseOrderNo(orderNo);
        if (local.isPresent()) {
            PoSyncRecord po = local.get();
            return new ManualPoStatus(po.getPoStatus(), po.getPoStatusText(),
                    Integer.valueOf(1).equals(po.getPoStatus()));
        }
        LingxingPurchaseOrderClient.PurchaseOrderData po = lingxingPurchaseOrderClient.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR,
                        "本地和领星均未找到采购单：" + orderNo));
        return new ManualPoStatus(po.status(), po.statusText(), Integer.valueOf(1).equals(po.status()));
    }

    public record ManualPoStatus(Integer status, String statusText, boolean pendingOrder) {
    }

    /**
     * 手动创建弹窗的领星预填。用户在弹窗里填完采购单号后立即调用：
     * 从领星供应商档案带出供方地址、统一社会信用代码、预付款比例、结算方式，
     * 以及默认收款账户的收款人 / 银行账号 / 开户行，再带上交货日期、合同金额与商品明细。
     * 只读查询 + 按需补齐本地 PO 缓存，不创建合同、不写操作日志。
     *
     * <p>顺带查一次这张采购单名下是否已有有效合同（{@code status != 5} 且未删除）。
     * 有的话把合同号与状态一并返回，前端在填单阶段就拦住 —— 否则用户把明细、数量、
     * 单价全改完，点提交才被告知「该采购单已存在未取消的合同」，白填一场。
     * 这里只做提示，真正的防重仍靠创建时的校验与 {@code uk_contract_active_po_no} 唯一索引。</p>
     */
    public ManualPoPrefill loadManualPoPrefill(String purchaseOrderNo) {
        if (!StringUtils.hasText(purchaseOrderNo)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "采购单号不能为空");
        }
        String orderNo = purchaseOrderNo.trim();
        PoSyncRecord po = loadManualPo(orderNo);
        LingxingSupplierClient.SupplierProfile profile = loadSupplierProfile(po.getSupplierId());

        String supplierAddress = profile == null ? null : profile.address();
        String supplierCreditCode = profile == null ? null : profile.creditCode();
        // 预付款比例按统一口径（两位小数）回填，前端输入框里看到的就是最终入库的样子。
        String prepayPercent = profile == null ? null : RatioText.of(profile.prepayPercent());
        String settlementMethod = profile == null ? null : profile.settlementMethod();
        LingxingSupplierClient.SupplierPaymentAccount account = profile == null
                ? null : profile.defaultPaymentAccount().orElse(null);

        LocalDate deliveryDate = po.getItems() == null ? null : po.getItems().stream()
                .filter(item -> item.getExpectArriveTime() != null)
                .map(PoSyncRecordItem::getExpectArriveTime)
                .min(LocalDate::compareTo)
                .orElse(null);
        BigDecimal contractAmount = originalAmountOf(po);

        // 领星没维护的字段要在前端明确提示，否则用户会以为是系统没带出来。
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(po.getSupplierName())) missing.add("供方名称");
        if (!StringUtils.hasText(po.getSupplierPhone())) missing.add("供方电话");
        if (!StringUtils.hasText(po.getContactPerson())) missing.add("供方联系人");
        if (!StringUtils.hasText(supplierAddress)) missing.add("供方地址");
        if (!StringUtils.hasText(supplierCreditCode)) missing.add("统一社会信用代码");
        if (!StringUtils.hasText(prepayPercent)) missing.add("预付款比例");
        if (!StringUtils.hasText(settlementMethod)) missing.add("结算方式");
        if (!StringUtils.hasText(account == null ? null : account.accountName())) missing.add("收款人");
        if (!StringUtils.hasText(account == null ? null : account.accountId())) missing.add("银行账号");
        if (!StringUtils.hasText(account == null ? null : account.bankName())) missing.add("开户行");
        if (deliveryDate == null) missing.add("交货日期");

        // 填单阶段就告知「这张采购单已经有合同了」，避免用户把明细改完才在提交时被拒。
        // 查询失败不阻断预填（前端大不了仍走提交时的那道校验），只记日志。
        ActiveContractRef existing = null;
        try {
            existing = contractRepository.findActiveByPurchaseOrderNo(orderNo).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("手动创建预填：查询采购单已有合同失败，跳过该提示：purchaseOrderNo={}", orderNo, ex);
        }

        log.info("手动创建领星预填完成：purchaseOrderNo={}, supplierId={}, 已有合同={}, 未带出字段={}",
                orderNo, po.getSupplierId(),
                existing == null ? "无" : existing.contractNo() + "(" + existing.statusText() + ")",
                missing.isEmpty() ? "无" : String.join("、", missing));

        return new ManualPoPrefill(
                orderNo,
                po.getSupplierName(),
                po.getSupplierPhone(),
                po.getContactPerson(),
                supplierAddress,
                supplierCreditCode,
                prepayPercent,
                settlementMethod,
                account == null ? null : account.accountName(),
                account == null ? null : account.accountId(),
                account == null ? null : account.bankName(),
                deliveryDate == null ? null : deliveryDate.toString(),
                contractAmount,
                buildPrefillItems(po.getItems()),
                List.copyOf(missing),
                existing == null ? null : existing.contractNo(),
                existing == null ? null : existing.statusText());
    }

    /**
     * 预填的商品明细：口径与建合同时的 {@code Contract.toItem} <b>完全一致</b>
     * （数量取实际采购量、单价取不含税、金额用同一算法、规格取领星 model），
     * 保证「创建前在表单里看到的」和「创建出来的合同明细」是同一份数据。
     *
     * <p>图片一律批量补齐：先用本地历史合同的同 SKU 图片（一次查库），仍未命中的 SKU
     * <b>一次性</b>批量查领星（{@code findBySkus}，超过接口上限由客户端内部再分批），
     * 不按明细逐条查。</p>
     */
    private List<ManualPoItem> buildPrefillItems(List<PoSyncRecordItem> poItems) {
        if (poItems == null || poItems.isEmpty()) {
            return List.of();
        }
        Set<String> missingPicSkus = poItems.stream()
                .filter(item -> !StringUtils.hasText(item.getPicUrl()) && StringUtils.hasText(item.getSku()))
                .map(PoSyncRecordItem::getSku)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> picUrlsBySku = resolvePrefillPicUrls(missingPicSkus);

        List<ManualPoItem> items = new ArrayList<>(poItems.size());
        for (PoSyncRecordItem item : poItems) {
            Integer quantity = PurchasePriceCalculator.effectiveQuantity(
                    item.getQuantityReal(), item.getQuantityPlan());
            // 明细自带的 pic_url 优先（采购单列表接口已返回），缺失才用补图结果
            String picUrl = StringUtils.hasText(item.getPicUrl())
                    ? item.getPicUrl() : picUrlsBySku.get(item.getSku());
            items.add(new ManualPoItem(
                    item.getSku(),
                    item.getProductName(),
                    item.getModel(),
                    quantity,
                    null,
                    item.getUnitPriceWithoutTax(),
                    PurchasePriceCalculator.lineAmount(item.getUnitPriceWithoutTax(), quantity),
                    picUrl));
        }
        return items;
    }

    /** 批量补图：本地历史合同缓存一次查库，剩余 SKU 一次查领星；任一步失败都只让图片留空，不阻断预填。 */
    private Map<String, String> resolvePrefillPicUrls(Collection<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        try {
            resolved.putAll(contractRepository.findLatestItemPicUrlsBySkus(skus));
        } catch (RuntimeException ex) {
            log.warn("手动创建预填：本地图片缓存查询失败，改为直接查领星：待查 SKU 数={}", skus.size(), ex);
        }
        List<String> remains = skus.stream().filter(sku -> !resolved.containsKey(sku)).toList();
        if (!remains.isEmpty()) {
            try {
                lingxingProductClient.findBySkus(remains).forEach((sku, product) -> {
                    if (product != null && StringUtils.hasText(product.picUrl())) {
                        resolved.put(sku, product.picUrl());
                    }
                });
            } catch (RuntimeException ex) {
                log.warn("手动创建预填：批量查询领星商品图失败，图片留空：待查 SKU 数={}", remains.size(), ex);
            }
        }
        return resolved;
    }

    /** 预填用的 PO：本地缺失或价格口径过期时按单号从领星补同步，与手动创建走同一套兜底。 */
    private PoSyncRecord loadManualPo(String orderNo) {
        Optional<PoSyncRecord> local = poSyncRepository.findByPurchaseOrderNo(orderNo);
        boolean stalePrice = local.isPresent() && needsPriceResync(local.get());
        if (local.isPresent() && !stalePrice) {
            return local.get();
        }
        if (stalePrice) {
            log.info("本地采购单缺少不含税单价，预填前重新同步：purchaseOrderNo={}", orderNo);
        }
        LingxingPurchaseOrderClient.PurchaseOrderData order = lingxingPurchaseOrderClient.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR,
                        stalePrice ? "本地采购单价格口径尚未更新，且领星未查到该采购单：" + orderNo
                                : "本地和领星均未找到采购单：" + orderNo));
        poSyncRepository.save(PoSyncAppService.toRecord(order, LocalDateTime.now()));
        return poSyncRepository.findByPurchaseOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ResultCode.SYSTEM_ERROR,
                        "采购单补充同步后读取失败：" + orderNo));
    }

    /** 领星远端波动不该阻断预填：拿不到档案就让对应字段留空，由前端提示人工补。 */
    private LingxingSupplierClient.SupplierProfile loadSupplierProfile(Long supplierId) {
        if (supplierId == null) {
            return null;
        }
        try {
            return lingxingSupplierClient.findSupplierProfile(supplierId).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("领星预填：查询供应商档案失败，供方扩展字段留空：supplierId={}", supplierId, ex);
            return null;
        }
    }

    private BigDecimal originalAmountOf(PoSyncRecord po) {
        if (po.getItems() == null || po.getItems().isEmpty()) {
            return null;
        }
        // 与建合同时的口径保持一致（数量取实际采购量，缺失退回计划采购量），
        // 否则创建表单里提示的合同金额会和真正建出来的合同金额不一致。
        BigDecimal total = po.getItems().stream()
                .map(item -> PurchasePriceCalculator.lineAmount(item.getUnitPriceWithoutTax(),
                        PurchasePriceCalculator.effectiveQuantity(item.getQuantityReal(), item.getQuantityPlan())))
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.signum() > 0 ? total : null;
    }

    /**
     * 手动创建表单的领星预填结果。
     * missingFields 列出领星侧没维护、需要人工补录的字段名，前端据此给出提示。
     * items 是采购单商品明细（含图片），前端用于创建前预览；
     * 允许人工改数量与不含税单价，改后的值随创建请求回传，由
     * {@link #applyItemOverrides} 覆盖到按采购单组装出来的明细上。
     *
     * <p>{@code existingContractNo} / {@code existingContractStatus}：这张采购单名下
     * 已有的有效合同（未取消、未删除）的编号与状态描述。非空时前端应直接阻断创建，
     * 提示用户先处理已有合同，而不是等到提交才报错。</p>
     */
    public record ManualPoPrefill(
            String purchaseOrderNo,
            String supplierName,
            String supplierPhone,
            String contactPerson,
            String supplierAddress,
            String supplierCreditCode,
            String prepaymentRatio,
            String paymentMethod,
            String supplierAccountName,
            String supplierBankAccount,
            String supplierBankName,
            String deliveryDate,
            BigDecimal contractAmount,
            List<ManualPoItem> items,
            List<String> missingFields,
            String existingContractNo,
            String existingContractStatus) {
    }

    /**
     * 预填用的商品明细行；字段与合同详情页明细表一一对应。
     * unit 与建合同时一致恒为 null（领星采购单没有单位字段），前端「数量（单位）」只显示数量。
     */
    public record ManualPoItem(
            String sku,
            String productName,
            String specification,
            Integer quantity,
            String unit,
            BigDecimal unitPrice,
            BigDecimal amount,
            String picUrl) {
    }

    /**
     * 自动创建合同（为指定 PO 列表创建合同）。
     *
     * @param purchaseOrderNos 待建合同的采购单号列表（null 或空表示扫描全表）
     * @return 汇总结果
     */
    public AutoCreateResult autoCreate(List<String> purchaseOrderNos) {
        log.info("合同自动创建开始，指定 PO 数={}", purchaseOrderNos == null ? "全表" : purchaseOrderNos.size());

        // 1. 查询默认需方公司
        Optional<BuyerCompany> buyerOpt = buyerCompanyRepository.findDefault();
        if (buyerOpt.isEmpty()) {
            log.error("无默认需方公司（priority=1 且 is_active=1），合同自动创建跳过");
            return new AutoCreateResult(0, 0, 0, 0);
        }
        BuyerCompany buyer = buyerOpt.get();

        // 2. 查询待建合同的 PO（按指定列表过滤）
        List<PoSyncRecord> pendingPos = (purchaseOrderNos == null || purchaseOrderNos.isEmpty())
                ? poSyncRepository.findPendingForContract()
                : poSyncRepository.findPendingForContractByOrderNos(purchaseOrderNos);

        if (pendingPos.isEmpty()) {
            log.info("无待建合同的 PO，合同自动创建结束");
            return new AutoCreateResult(0, 0, 0, 0);
        }

        // 3. 逐条处理
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (PoSyncRecord po : pendingPos) {
            String poNo = po.getPurchaseOrderNo();
            try {
                boolean success = processOnePo(po, buyer);
                if (success) {
                    created++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.error("合同创建失败，poNo={}", poNo, ex);
            }
        }

        AutoCreateResult result = new AutoCreateResult(pendingPos.size(), created, skipped, failed);
        log.info("合同自动创建结束：拉取 {}，创建 {}，跳过 {}，失败 {}",
                result.total(), result.created(), result.skipped(), result.failed());
        return result;
    }

    /**
     * 处理一条 PO（加事务：合同创建 + PO 标记原子）。
     *
     * @return true=创建，false=跳过
     */
    @Transactional
    protected boolean processOnePo(PoSyncRecord po, BuyerCompany buyer) {
        String poNo = po.getPurchaseOrderNo();

        String defaultAccountName;
        try {
            defaultAccountName = po.getSupplierId() == null ? null
                    : lingxingSupplierClient.findSupplierProfile(po.getSupplierId())
                    .flatMap(LingxingSupplierClient.SupplierProfile::defaultPaymentAccount)
                    .map(LingxingSupplierClient.SupplierPaymentAccount::accountName)
                    .orElse(null);
        } catch (RuntimeException ex) {
            String reason = "PO=" + poNo + "，供应商=" + po.getSupplierName()
                    + "，跳过原因：查询领星默认收款账户失败，无法进行账户名称白名单核验";
            contractRepository.saveOperationLog(ContractOperationLog.ofCreateSkipped(poNo, reason));
            log.warn("跳过建合同：{}", reason, ex);
            return false;
        }

        if (!StringUtils.hasText(defaultAccountName)) {
            String reason = "PO=" + poNo + "，供应商=" + po.getSupplierName()
                    + "，跳过原因：领星未维护默认收款账户名称，无法进行白名单核验";
            contractRepository.saveOperationLog(ContractOperationLog.ofCreateSkipped(poNo, reason));
            log.info("跳过建合同：{}", reason);
            return false;
        }

        if (!supplierWhitelist.contains(defaultAccountName)) {
            String reason = "PO=" + poNo + "，供应商=" + po.getSupplierName() + "，默认收款账户名称="
                    + defaultAccountName + "，跳过原因：默认收款账户名称不在采购合同签约白名单";
            contractRepository.saveOperationLog(ContractOperationLog.ofCreateSkipped(poNo, reason));
            log.info("跳过建合同：{}", reason);
            return false;
        }

        ContractCreationValidator.ValidationResult validation = contractCreationValidator.validate(po, buyer);
        if (!validation.missingFields().isEmpty()) {
            String reason = "PO=" + poNo + "，缺失/不满足字段：" + String.join("、", validation.missingFields());
            contractRepository.saveOperationLog(ContractOperationLog.ofCreateSkipped(poNo, reason));
            log.warn("跳过建合同：{}", reason);
            return false;
        }

        // 校验 1：supplier_name 必填
        if (!StringUtils.hasText(po.getSupplierName())) {
            log.warn("跳过建合同（supplier_name 为空）：poNo={}", poNo);
            return false;
        }


        // 校验 2：supplier_phone 必填（签署必需）
        if (!StringUtils.hasText(po.getSupplierPhone())) {
            log.warn("跳过建合同（supplier_phone 为空）：poNo={}", poNo);
            return false;
        }

        // 校验 3：contact_person 必填
        if (!StringUtils.hasText(po.getContactPerson())) {
            log.warn("跳过建合同（contact_person 为空）：poNo={}", poNo);
            return false;
        }

        // 校验 4：必须有明细
        if (po.getItems() == null || po.getItems().isEmpty()) {
            log.warn("跳过建合同（无明细）：poNo={}", poNo);
            return false;
        }

        // 校验 5：需方公司地址必填（用于签订地点）
        if (!StringUtils.hasText(buyer.getAddress())) {
            log.warn("跳过建合同（需方公司地址为空）：poNo={}, buyerCompanyId={}", poNo, buyer.getId());
            return false;
        }

        // 校验 6：唯一性兜底
        if (contractRepository.existsActiveByPurchaseOrderNo(poNo)) {
            log.warn("跳过建合同（已存在非取消合同）：poNo={}", poNo);
            recordDuplicateCreateSkipped(poNo);
            return false;
        }

        // 生成合同编号
        String contractNo = generateContractNo();

        // 创建合同聚合
        Contract contract = Contract.createFromPo(po, buyer, contractNo);
        contract.setTemplateId(contractTemplateService.resolveDefaultTemplateId(contract.getContractType()));
        enrichSupplierAddress(contract);

        // 从领星API获取并填充商品图片URL（复用校验阶段已查回的结果，不再重复查领星）
        enrichContractItemsWithImages(contract, validation.picUrlsBySku());

        // 原子保存：合同主表 + 明细 + CREATE 日志
        long contractId;
        try {
            contractId = contractRepository.create(contract);
        } catch (DuplicateKeyException ex) {
            // 上面的 existsActiveByPurchaseOrderNo 是「先查后写」，与另一个实例的创建存在竞态窗口；
            // contract.uk_contract_active_po_no 是最终兜底，撞上说明确有并发，按「已存在」跳过即可。
            log.warn("跳过建合同（并发重复创建，命中唯一约束）：poNo={}", poNo, ex);
            recordDuplicateCreateSkipped(poNo);
            return false;
        }

        // 回写 PO 标记
        poSyncRepository.markContractCreated(poNo, contractId);

        log.info("合同创建成功：poNo={}, contractNo={}, contractId={}", poNo, contractNo, contractId);

        // 首次创建完成即保存 PDF；后续未编辑时签署可直接复用，避免再次拉取图片和填充模板。
        generateInitialContractPdf(contract);
        return true;
    }

    /** 重复创建跳过日志独立提交，避免被失败的合同插入事务回滚。 */
    private void recordDuplicateCreateSkipped(String purchaseOrderNo) {
        try {
            var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.executeWithoutResult(status -> {
                String details = "PO=" + purchaseOrderNo
                        + "；该采购单已存在未取消的合同，本次自动创建已跳过";
                try {
                    ActiveContractRef existing = contractRepository.findActiveByPurchaseOrderNo(purchaseOrderNo).orElse(null);
                    if (existing != null) {
                        details += "；已有合同=" + existing.contractNo() + "；合同状态=" + existing.statusText();
                    }
                } catch (RuntimeException ex) {
                    log.warn("重复创建跳过日志查询已有合同失败：purchaseOrderNo={}", purchaseOrderNo, ex);
                }
                contractRepository.saveOperationLog(ContractOperationLog.ofCreateSkipped(purchaseOrderNo, details));
            });
        } catch (RuntimeException ex) {
            // 审计写入失败保留服务日志，不能阻断剩余采购单的自动创建。
            log.error("自动创建跳过日志保存失败：purchaseOrderNo={}", purchaseOrderNo, ex);
        }
    }

    /**
     * 生成合同编号（"HT" + yyyyMMdd + 4位随机 + 去重重试）。
     */
    private String generateContractNo() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        for (int attempts = 0; attempts < 50; attempts++) {
            String contractNo = "HT" + date + ThreadLocalRandom.current().nextInt(1000, 10000);
            if (contractRepository.findByContractNo(contractNo).isEmpty()) {
                return contractNo;
            }
        }
        throw new RuntimeException("合同编号生成失败，50 次重试均冲突");
    }

    /**
     * 手动创建单个合同（指定采购单号）。
     *
     * <p>整个方法是同步的，慢在从领星补商品图与生成 PDF，前端几十秒看不到任何反馈。
     * 因此额外接收一个 {@code progressKey}：全程把「第几步 / 在做什么」写进
     * {@link ContractCreateProgressTracker}，供前端并行轮询显示小字步骤提示。
     * 该参数只影响展示，为 null（定时任务、老客户端）时所有进度写入都会静默跳过。
     *
     * @param request      创建合同请求，包含采购单号
     * @param creatorEmail 操作人邮箱，写入创建人
     * @param progressKey  前端生成的进度标识，可为 null
     * @return 创建结果（包含合同ID、合同编号、文件URL） 响应对象
     */
    public ContractCreateResponse createContract(ContractCreateRequest request, String creatorEmail, String progressKey) {
        String purchaseOrderNo = request.getPurchaseOrderNo();
        log.info("手动创建合同开始：purchaseOrderNo={}, progressKeyPresent={}",
                purchaseOrderNo, StringUtils.hasText(progressKey));
        contractCreateProgressTracker.begin(progressKey, "正在校验采购单与需方公司信息");
        try {
            return doCreateContract(request, creatorEmail, progressKey);
        } finally {
            // 成功或失败都清掉，进度只是过程展示，不需要保留。
            contractCreateProgressTracker.clear(progressKey);
        }
    }

    private ContractCreateResponse doCreateContract(ContractCreateRequest request, String creatorEmail, String progressKey) {
        String purchaseOrderNo = request.getPurchaseOrderNo();
        // 1. 不传需方公司时使用 priority=1；传入时使用页面下拉框选中的公司。
        BuyerCompany buyer = resolveManualBuyerCompany(request.getBuyerCompanyId());

        // 2. 优先使用本地已同步的 PO；仅本地缺失时按单号从领星补查旧 PO。
        Optional<PoSyncRecord> poOpt = poSyncRepository.findByPurchaseOrderNo(purchaseOrderNo);
        boolean oldPriceRecord = poOpt.isPresent() && needsPriceResync(poOpt.get());
        if (poOpt.isEmpty() || oldPriceRecord) {
            if (oldPriceRecord) {
                log.info("本地采购单缺少不含税单价，创建前重新同步：purchaseOrderNo={}", purchaseOrderNo);
            }
            LingxingPurchaseOrderClient.PurchaseOrderData order = lingxingPurchaseOrderClient
                    .findByOrderNo(purchaseOrderNo)
                    .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR,
                            oldPriceRecord ? "本地采购单价格口径尚未更新，且领星未查到该采购单：" + purchaseOrderNo
                                    : "本地和领星均未找到采购单：" + purchaseOrderNo));
            poSyncRepository.save(PoSyncAppService.toRecord(order, LocalDateTime.now()));
            poOpt = poSyncRepository.findByPurchaseOrderNo(purchaseOrderNo);
        }
        if (poOpt.isEmpty()) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "采购单补充同步后读取失败：" + purchaseOrderNo);
        }
        PoSyncRecord po = poOpt.get();

        // 3. 手动创建不受自动创建的“待下单”状态限制；保留实际状态便于排查。
        log.info("手动创建采购单状态：purchaseOrderNo={}, poStatus={}, poStatusText={}",
                purchaseOrderNo, po.getPoStatus(), po.getPoStatusText());

        // 4. 唯一性检查。手动字段将在合同组装后统一校验，允许补齐领星缺失数据。
        //    一次查询同时拿到「有没有」和「是哪一份」，报错时能直接告诉用户撞的是哪张合同。
        ActiveContractRef existingContract = contractRepository.findActiveByPurchaseOrderNo(purchaseOrderNo).orElse(null);
        if (existingContract != null) {
            log.warn("手动创建被拒：采购单已有有效合同，purchaseOrderNo={}, 已有合同={}({})",
                    purchaseOrderNo, existingContract.contractNo(), existingContract.statusText());
            throw new BusinessException(ResultCode.CONTRACT_PURCHASE_ORDER_ALREADY_EXISTS,
                    "该采购单已存在合同 " + existingContract.contractNo()
                            + "（" + existingContract.statusText() + "），不能重复创建");
        }

        contractCreateProgressTracker.advance(progressKey, ContractCreateProgressTracker.STEP_ASSEMBLE,
                "正在根据采购单组装合同数据");

        // 5. 生成合同编号
        String contractNo = generateContractNo();

        // 6. 创建合同聚合
        Contract contract = Contract.createFromPo(po, buyer, contractNo);
        contract.setTemplateId(contractTemplateService.resolveDefaultTemplateId(contract.getContractType()));
        contract.setCreatorId(creatorEmail);
        contract.setCreatorName(creatorEmail);
        contract.setCreateType(Contract.CREATE_TYPE_MANUAL);

        // 7. 手动补充字段（覆盖领星和默认数据）
        applyManualOverrides(contract, request);

        // 8. 从领星API获取并填充商品图片URL（剩余 SKU 一次性批量查）
        //    手动创建没有前置校验阶段的结果可复用，传空 Map。
        enrichContractItemsWithImages(contract, progressKey, Map.of());

        contractCreateProgressTracker.advance(progressKey, ContractCreateProgressTracker.STEP_SAVE,
                "正在校验必填字段并保存合同");

        // 9. 以最终合同数据做统一必填校验；签署时间允许为空。
        validateManualRequiredFields(contract);

        // 10. 原子保存：合同主表 + 明细 + CREATE 日志
        long contractId;
        try {
            contractId = contractRepository.create(contract);
        } catch (DuplicateKeyException ex) {
            // 第 4 步的 findActiveByPurchaseOrderNo 是「先查后写」，而本方法在检查之后还要
            // 改明细、批量查领星图片、压图，窗口有几秒到几十秒；若期间定时任务或另一次点击
            // 抢先落库，这里由 contract.uk_contract_active_po_no 拦下，转成与前置检查一致的业务提示。
            log.warn("手动创建合同命中唯一约束（并发重复创建）：purchaseOrderNo={}", purchaseOrderNo, ex);
            throw new BusinessException(ResultCode.CONTRACT_PURCHASE_ORDER_ALREADY_EXISTS);
        }

        // 11. 回写 PO 标记
        poSyncRepository.markContractCreated(purchaseOrderNo, contractId);

        log.info("合同创建成功：poNo={}, contractNo={}, contractId={}", purchaseOrderNo, contractNo, contractId);

        contractCreateProgressTracker.advance(progressKey, ContractCreateProgressTracker.STEP_PDF,
                "合同已保存，正在生成合同文件");

        // 创建时生成首版 PDF；合同编辑会清空该地址，后续下载/签署才会按需重建。
        String fileUrl = null;
        try {
            fileUrl = generateInitialContractPdf(contract);
        } catch (Exception ex) {
            log.error("手动创建首版合同PDF失败：contractNo={}, contractId={}", contractNo, contractId, ex);
        }

        // 11. 返回结果
        return new ContractCreateResponse(
                contractId,
                contractNo,
                purchaseOrderNo,
                fileUrl,
                "合同创建成功"
        );
    }

    /** 旧同步记录没有不含税单价时，仅在首次使用时补查一次领星。 */
    private boolean needsPriceResync(PoSyncRecord po) {
        return po.getItems() == null || po.getItems().isEmpty()
                || po.getItems().stream().anyMatch(item -> item.getUnitPriceWithoutTax() == null);
    }

    /**
     * 应用手动补充的字段（覆盖领星和默认数据）。
     */
    private String generateInitialContractPdf(Contract contract) {
        try {
            log.info("创建合同后生成首版PDF：contractNo={}", contract.getContractNo());
            byte[] pdfBytes = contractPdfConverter.convert(contractTemplateService.fillTemplate(contract),
                    contract.getContractNo());
            String fileUrl = contractFileStore.store(contract.getContractNo(), pdfBytes, "pdf");
            contractRepository.updatePdfUrl(contract.getId(), fileUrl);
            contract.setContractPdfUrl(fileUrl);
            log.info("合同首版PDF已保存：contractNo={}, bytes={}", contract.getContractNo(), pdfBytes.length);
            return fileUrl;
        } catch (Exception ex) {
            throw new RuntimeException("生成合同首版PDF失败", ex);
        }
    }

    private void applyManualOverrides(Contract contract, ContractCreateRequest request) {
        // 明细的数量 / 不含税单价允许人工改：先按 SKU 覆盖并重算金额，再做金额与折扣覆盖，
        // 否则「主表原价 = Σ明细金额」会被打破（合同 PDF 底部的合计直接取主表金额）。
        applyItemOverrides(contract, request.getItems());

        if (request.getPrepaymentRatio() != null) {
            // 统一保留两位小数入库：填 0 存 "0.00"，填 0.3 存 "0.30"。
            contract.setPrepayPercent(RatioText.of(request.getPrepaymentRatio()));
        }
        if (StringUtils.hasText(request.getPaymentMethod())) {
            contract.setSettlementMethod(request.getPaymentMethod());
        }
        // 供方信息覆盖
        if (StringUtils.hasText(request.getSupplierName())) {
            contract.setSupplierName(request.getSupplierName());
            log.info("手动覆盖供方名称：{}", request.getSupplierName());
        }
        if (StringUtils.hasText(request.getSupplierAddress())) {
            contract.setSupplierAddress(request.getSupplierAddress());
            log.info("手动覆盖供方地址：{}", request.getSupplierAddress());
        }
        if (StringUtils.hasText(request.getContactPerson())) {
            contract.setContactPerson(request.getContactPerson());
            log.info("手动覆盖联系人：{}", request.getContactPerson());
        }
        if (StringUtils.hasText(request.getSupplierPhone())) {
            contract.setSupplierPhone(request.getSupplierPhone());
            log.info("手动覆盖供方电话：{}", request.getSupplierPhone());
        }
        if (StringUtils.hasText(request.getSupplierCreditCode())) {
            contract.setSupplierCreditCode(request.getSupplierCreditCode());
            log.info("手动填写供方统一社会信用代码：contractNo={}", contract.getContractNo());
        }
        if (StringUtils.hasText(request.getSupplierAccountName())) {
            contract.setSupplierAccountName(request.getSupplierAccountName());
            log.info("手动覆盖供方收款人：contractNo={}", contract.getContractNo());
        }
        if (StringUtils.hasText(request.getSupplierBankAccount())) {
            contract.setSupplierBankAccount(request.getSupplierBankAccount());
            log.info("手动覆盖供方银行账号：contractNo={}", contract.getContractNo());
        }
        if (StringUtils.hasText(request.getSupplierBankName())) {
            contract.setSupplierBankName(request.getSupplierBankName());
            log.info("手动覆盖供方开户行：contractNo={}", contract.getContractNo());
        }

        // 合同金额覆盖
        if (request.getContractAmount() != null) {
            contract.setContractAmount(request.getContractAmount());
            contract.setOriginalAmount(request.getContractAmount());
            log.info("手动覆盖合同金额：{}", request.getContractAmount());
        }
        if (request.getDiscountedAmount() != null) {
            applyDiscount(contract, request.getDiscountedAmount());
            log.info("手动填写合同折扣：contractNo={}, discount={}, finalAmount={}",
                    contract.getContractNo(), request.getDiscountedAmount(), contract.getContractAmount());
        }

        // 合同日期覆盖
        if (StringUtils.hasText(request.getContractDate())) {
            try {
                LocalDate date = LocalDate.parse(request.getContractDate());
                contract.setContractDate(date);
                log.info("手动覆盖合同日期：{}", date);
            } catch (Exception ex) {
                log.warn("合同日期格式错误，使用默认值：{}", request.getContractDate());
            }
        }

        // 交货日期覆盖
        if (StringUtils.hasText(request.getDeliveryDate())) {
            try {
                LocalDate date = LocalDate.parse(request.getDeliveryDate());
                contract.setDeliveryDate(date);
                log.info("手动覆盖交货日期：{}", date);
            } catch (Exception ex) {
                log.warn("交货日期格式错误，忽略：{}", request.getDeliveryDate());
            }
        }
    }

    /**
     * 用创建表单回传的覆盖值改写明细的数量 / 不含税单价。
     *
     * <p>只改数量与单价，SKU / 品名 / 图片等仍以采购单为准；改完立刻重算明细金额、原价与合同金额，
     * 保证「主表金额 = Σ明细金额」这条不变量在手动改价后依然成立。匹配规则见 {@link #matchItem}。</p>
     *
     * <p>金额始终按「不含税单价 × 数量」重算：任一项为空就置空，交给后面的必填校验报错，
     * 而不是保留旧金额 —— 否则会出现「数量改了、金额还是旧的」这种对不上账的合同。</p>
     */
    private void applyItemOverrides(Contract contract, List<ContractCreateRequest.ItemOverride> overrides) {
        if (overrides == null || overrides.isEmpty()
                || contract.getItems() == null || contract.getItems().isEmpty()) {
            return;
        }
        List<ContractItem> items = contract.getItems();

        int matched = 0;
        int ignored = 0;
        for (ContractCreateRequest.ItemOverride override : overrides) {
            if (override == null) {
                continue;
            }
            // 两个值都为空说明这条没改，忽略，避免把它当成「把数量改成 null」
            if (override.getQuantity() == null && override.getUnitPrice() == null) {
                continue;
            }
            ContractItem item = matchItem(items, override);
            if (item == null) {
                ignored++;
                log.warn("明细覆盖值未匹配到采购商品，已忽略：contractNo={}, index={}, sku={}",
                        contract.getContractNo(), override.getIndex(), override.getSku());
                continue;
            }
            if (override.getQuantity() != null) {
                item.setQuantity(override.getQuantity());
            }
            if (override.getUnitPrice() != null) {
                item.setUnitPrice(override.getUnitPrice());
            }
            item.setAmount(PurchasePriceCalculator.lineAmount(item.getUnitPrice(), item.getQuantity()));
            matched++;
        }
        if (matched == 0) {
            log.warn("明细覆盖值全部未匹配，金额保持采购单原值：contractNo={}, 未命中={}",
                    contract.getContractNo(), ignored);
            return;
        }

        BigDecimal total = contract.getItems().stream()
                .map(ContractItem::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        contract.setOriginalAmount(total);
        contract.setContractAmount(total);
        log.info("手动覆盖商品明细数量/单价：contractNo={}, 命中明细={}, 未命中={}, 重算原价={}",
                contract.getContractNo(), matched, ignored, total);
    }

    /**
     * 定位一条明细：优先按「序号 + SKU」命中（同一 SKU 可能重复出现，序号才唯一），
     * 序号缺失或该位置 SKU 对不上时退回按 SKU 匹配第一条。
     */
    private ContractItem matchItem(List<ContractItem> items, ContractCreateRequest.ItemOverride override) {
        String sku = override.getSku() == null ? null : override.getSku().trim();
        Integer index = override.getIndex();
        if (index != null && index >= 0 && index < items.size()) {
            ContractItem candidate = items.get(index);
            if (sku != null && sku.equals(candidate.getSku())) {
                return candidate;
            }
        }
        if (!StringUtils.hasText(sku)) {
            return null;
        }
        return items.stream()
                .filter(item -> sku.equals(item.getSku()))
                .findFirst()
                .orElse(null);
    }

    private void validateManualRequiredFields(Contract contract) {
        List<String> missing = new java.util.ArrayList<>();
        if (!StringUtils.hasText(contract.getContractNo())) missing.add("合同编号");
        if (!StringUtils.hasText(contract.getContractName())) missing.add("合同名称");
        if (contract.getContractType() == null) missing.add("合同类型");
        if (contract.getStatus() == null) missing.add("合同状态");
        if (!StringUtils.hasText(contract.getSupplierName())) missing.add("供方");
        if (!StringUtils.hasText(contract.getSupplierAddress())) missing.add("供方地址");
        if (!StringUtils.hasText(contract.getContactPerson())) missing.add("供方联系人");
        if (!StringUtils.hasText(contract.getSupplierPhone())) missing.add("供方电话");
        if (!StringUtils.hasText(contract.getSupplierCreditCode())) missing.add("供方统一社会信用代码");
        if (!StringUtils.hasText(contract.getBuyerCompanyName())) missing.add("需方");
        if (!StringUtils.hasText(contract.getBuyerAddress())) missing.add("签订地点");
        if (!StringUtils.hasText(contract.getSupplierBankAccount())) missing.add("供方银行账户");
        if (!StringUtils.hasText(contract.getSupplierBankName())) missing.add("供方开户行");
        // 收款人缺失时合同 PDF 会整段省略收款账户信息，因此与账号、开户行同为必填。
        if (!StringUtils.hasText(contract.getSupplierAccountName())) missing.add("供方收款人");
        if (!StringUtils.hasText(contract.getPrepayPercent())) missing.add("预付款");
        if (!StringUtils.hasText(contract.getSettlementMethod())) missing.add("结算方式");
        if (contract.getDeliveryDate() == null) missing.add("交付日期");
        if (contract.getContractAmount() == null) missing.add("合同金额");
        if (contract.getItems() == null || contract.getItems().isEmpty()) {
            missing.add("采购商品明细");
        } else {
            for (ContractItem item : contract.getItems()) {
                String label = StringUtils.hasText(item.getSku()) ? "SKU=" + item.getSku() : "采购商品";
                if (!StringUtils.hasText(item.getSku())) missing.add(label + " SKU");
                if (!StringUtils.hasText(item.getPicUrl())) missing.add(label + " 图片");
                if (!StringUtils.hasText(item.getProductName())) missing.add(label + " 品名及规格");
                if (item.getQuantity() == null || item.getQuantity() <= 0) missing.add(label + " 数量");
                if (item.getUnitPrice() == null) missing.add(label + " 不含税单价");
            }
        }
        if (!missing.isEmpty()) {
            throw new com.scione.scm.bill.common.BusinessException(
                    com.scione.scm.bill.common.ResultCode.PARAM_ERROR,
                    "无法创建合同，以下字段不能为空：" + String.join("、", missing));
        }
    }

    /** 模板金额区：原价固定取 originalAmount，实际金额始终为原价减折扣。 */
    private void applyDiscount(Contract contract, java.math.BigDecimal discount) {
        java.math.BigDecimal original = contract.getOriginalAmount() == null
                ? java.math.BigDecimal.ZERO : contract.getOriginalAmount();
        if (discount.compareTo(java.math.BigDecimal.ZERO) < 0) {
            throw new com.scione.scm.bill.common.BusinessException(
                    com.scione.scm.bill.common.ResultCode.PARAM_ERROR, "折扣金额不能小于0");
        }
        if (discount.compareTo(original) > 0) {
            throw new com.scione.scm.bill.common.BusinessException(
                    com.scione.scm.bill.common.ResultCode.PARAM_ERROR, "折扣金额不能大于原价");
        }
        contract.setDiscountedAmount(discount);
        contract.setContractAmount(original.subtract(discount));
    }

    private BuyerCompany resolveManualBuyerCompany(Long buyerCompanyId) {
        Optional<BuyerCompany> buyerOpt = buyerCompanyId == null
                ? buyerCompanyRepository.findDefault()
                : buyerCompanyRepository.findById(buyerCompanyId);
        BuyerCompany buyer = buyerOpt.orElseThrow(() -> new com.scione.scm.bill.common.BusinessException(
                com.scione.scm.bill.common.ResultCode.PARAM_ERROR,
                buyerCompanyId == null ? "无默认需方公司（priority=1 且 is_active=1）" : "选择的需方公司不存在"));
        if (!Integer.valueOf(1).equals(buyer.getIsActive())) {
            throw new com.scione.scm.bill.common.BusinessException(
                    com.scione.scm.bill.common.ResultCode.PARAM_ERROR, "选择的需方公司未启用");
        }
        return buyer;
    }

    /**
     * 从领星API获取商品图片并填充到合同明细中（自动创建：无进度上报）。
     *
     * @param preResolvedPicUrls 校验阶段已批量查回的图片（sku → URL），可为 null；
     *                           命中的 SKU 不再重复查询，省掉一次对领星的重复调用
     */
    private void enrichContractItemsWithImages(Contract contract, Map<String, String> preResolvedPicUrls) {
        enrichContractItemsWithImages(contract, null, preResolvedPicUrls);
    }

    /**
     * 从领星API获取商品图片并填充到合同明细中。
     *
     * <p>取图一律走批量，三段都是「一次」：先定掉明细自带与校验阶段结果的明细，
     * 剩余 SKU 去重后<b>一次 SQL</b> 查历史合同图片，仍未命中的再<b>一次 HTTP</b> 交给
     * {@code findBySkus}（超接口上限时由客户端内部再分批）。
     * 不再按明细逐条 {@code findBySku} 或逐条查库——数据库在海外时，逐条查库单是网络往返
     * 就要 0.3s/条，18 条明细会多花 5s 以上。</p>
     *
     * <p>创建合同是同步长请求，这里仍是较慢的一段，因此上报一次进度文案
     * （{@code 正在获取商品图片（共 N 个商品）}）；{@code progressKey} 为 null 时不上报。</p>
     *
     * @param preResolvedPicUrls 校验阶段已批量查回的图片（sku → URL），可为 null
     */
    private void enrichContractItemsWithImages(Contract contract, String progressKey, Map<String, String> preResolvedPicUrls) {
        String poNo = contract.getPurchaseOrderNo();
        log.info("开始从领星API获取商品图片：contractNo={}, poNo={}", contract.getContractNo(), poNo);

        try {
            List<ContractItem> contractItems = contract.getItems();
            if (contractItems == null || contractItems.isEmpty()) {
                log.warn("合同无明细，跳过图片获取：contractNo={}", contract.getContractNo());
                return;
            }

            int preResolvedCount = 0;
            int memoryHitCount = 0;
            int picCacheHitCount = 0;
            int imageFoundCount = 0;
            int imageNotFoundCount = 0;
            int imageErrorCount = 0;

            // 第一遍：把「明细自带图片」「校验阶段结果」两条纯内存路径定掉，
            // 其余按 SKU 去重收集（同一 SKU 可能有多条明细，需保留全部引用）
            Map<String, List<ContractItem>> pendingBySku = new LinkedHashMap<>();
            for (ContractItem contractItem : contractItems) {
                if (StringUtils.hasText(contractItem.getPicUrl())) {
                    // 明细自带（来自 po_sync_record_item.pic_url），零 IO
                    memoryHitCount++;
                    continue;
                }
                String sku = contractItem.getSku();
                if (sku == null || sku.isBlank()) {
                    log.warn("合同明细SKU为空，跳过图片获取：contractNo={}", contract.getContractNo());
                    imageNotFoundCount++;
                    continue;
                }

                // 校验阶段已经查回来过，直接用，避免对领星重复调用
                String preResolved = preResolvedPicUrls == null ? null : preResolvedPicUrls.get(sku);
                if (StringUtils.hasText(preResolved)) {
                    contractItem.setPicUrl(preResolved);
                    preResolvedCount++;
                    log.debug("复用校验阶段查回的商品图片：sku={}", sku);
                    continue;
                }

                pendingBySku.computeIfAbsent(sku, key -> new ArrayList<>()).add(contractItem);
            }

            // 第二遍：一次 SQL 取回全部待查 SKU 的历史合同图片。
            // 绝不能按 SKU 逐条查库——数据库在海外，单次往返约 0.3s，18 条明细就是 5s 以上。
            Set<String> remoteSkus = new LinkedHashSet<>();
            List<ContractItem> remoteItems = new ArrayList<>();
            if (!pendingBySku.isEmpty()) {
                Map<String, String> cachedPicUrls = Map.of();
                try {
                    cachedPicUrls = contractRepository.findLatestItemPicUrlsBySkus(pendingBySku.keySet());
                } catch (Exception ex) {
                    // 查库失败不阻断：这几个 SKU 直接退化为查领星
                    log.warn("合同明细图片缓存批量查询失败（改为直接查领星）：contractNo={}, 待查 SKU 数={}",
                            contract.getContractNo(), pendingBySku.size(), ex);
                }
                for (Map.Entry<String, List<ContractItem>> entry : pendingBySku.entrySet()) {
                    String cached = cachedPicUrls.get(entry.getKey());
                    if (StringUtils.hasText(cached)) {
                        for (ContractItem contractItem : entry.getValue()) {
                            contractItem.setPicUrl(cached);
                            picCacheHitCount++;
                        }
                        log.debug("复用历史合同图片缓存：sku={}, 命中明细={}", entry.getKey(), entry.getValue().size());
                    } else {
                        remoteSkus.add(entry.getKey());
                        remoteItems.addAll(entry.getValue());
                    }
                }
            }

            // 第二遍：剩余 SKU 一次性批量查领星（接口接受 skus 数组，超上限时内部自动分批）
            if (!remoteItems.isEmpty()) {
                contractCreateProgressTracker.advance(progressKey, ContractCreateProgressTracker.STEP_IMAGES,
                        "正在获取商品图片（共 " + remoteSkus.size() + " 个商品）");

                Map<String, LingxingProductClient.ProductDetail> products;
                try {
                    log.debug("批量查询商品详情：sku 数={}", remoteSkus.size());
                    products = lingxingProductClient.findBySkus(remoteSkus);
                } catch (Exception ex) {
                    // 整批失败不影响合同创建，这些明细留待详情页展示或下次创建时再补
                    log.error("批量获取商品图片失败（继续处理其他明细）：contractNo={}, poNo={}, 待查 SKU 数={}",
                            contract.getContractNo(), poNo, remoteSkus.size(), ex);
                    products = null;
                }

                if (products == null) {
                    imageErrorCount += remoteItems.size();
                } else {
                    for (ContractItem contractItem : remoteItems) {
                        LingxingProductClient.ProductDetail product = products.get(contractItem.getSku());
                        String picUrl = product == null ? null : product.picUrl();
                        if (StringUtils.hasText(picUrl)) {
                            contractItem.setPicUrl(picUrl);
                            imageFoundCount++;
                            log.info("获取商品图片成功：sku={}, picUrl={}", contractItem.getSku(), picUrl);
                        } else {
                            log.warn("领星API未找到商品图片：sku={}", contractItem.getSku());
                            imageNotFoundCount++;
                        }
                    }
                }
            }

            log.info("合同明细图片处理完成：contractNo={}, 校验阶段复用={}, 明细自带={}, 历史合同缓存={}, 领星查询成功={}, 未找到={}, 失败={}",
                    contract.getContractNo(), preResolvedCount, memoryHitCount, picCacheHitCount,
                    imageFoundCount, imageNotFoundCount, imageErrorCount);

        } catch (Exception ex) {
            // 整体失败也不影响合同创建
            log.error("从领星获取商品图片失败（合同创建继续）：contractNo={}, poNo={}",
                    contract.getContractNo(), poNo, ex);
        }
    }

    private void enrichSupplierAddress(Contract contract) {
        if (contract.getSupplierId() == null) {
            return;
        }
        try {
            lingxingSupplierClient.findSupplierProfile(contract.getSupplierId()).ifPresent(profile -> {
                if (StringUtils.hasText(profile.address())) contract.setSupplierAddress(profile.address());
                if (StringUtils.hasText(profile.creditCode())) contract.setSupplierCreditCode(profile.creditCode());
                if (StringUtils.hasText(profile.prepayPercent())) {
                    contract.setPrepayPercent(RatioText.of(profile.prepayPercent()));
                }
                if (StringUtils.hasText(profile.settlementMethod())) contract.setSettlementMethod(profile.settlementMethod());
                profile.defaultPaymentAccount().ifPresent(account -> {
                    contract.setSupplierAccountName(account.accountName());
                    contract.setSupplierBankAccount(account.accountId());
                    contract.setSupplierBankName(account.bankName());
                });
            });
        } catch (RuntimeException ex) {
            // 创建前已做必填校验；此处仅防御性保护，避免远端短暂波动覆盖已校验的数据。
            log.warn("回填供方地址失败：supplierId={}", contract.getSupplierId());
        }
    }

    /**
     * 单次自动创建汇总。
     */
    public record AutoCreateResult(int total, int created, int skipped, int failed) {
    }
}
