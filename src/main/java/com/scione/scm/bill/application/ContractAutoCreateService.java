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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

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
     * 以及默认收款账户的收款人 / 银行账号 / 开户行，再带上交货日期与合同金额。
     * 只读查询 + 按需补齐本地 PO 缓存，不创建合同、不写操作日志。
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

        log.info("手动创建领星预填完成：purchaseOrderNo={}, supplierId={}, 未带出字段={}",
                orderNo, po.getSupplierId(), missing.isEmpty() ? "无" : String.join("、", missing));

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
                List.copyOf(missing));
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
            List<String> missingFields) {
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

        List<String> missingFields = contractCreationValidator.validate(po, buyer);
        if (!missingFields.isEmpty()) {
            String reason = "PO=" + poNo + "，缺失/不满足字段：" + String.join("、", missingFields);
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
            return false;
        }

        // 生成合同编号
        String contractNo = generateContractNo();

        // 创建合同聚合
        Contract contract = Contract.createFromPo(po, buyer, contractNo);
        contract.setTemplateId(contractTemplateService.resolveDefaultTemplateId(contract.getContractType()));
        enrichSupplierAddress(contract);

        // 从领星API获取并填充商品图片URL
        enrichContractItemsWithImages(contract);

        // 原子保存：合同主表 + 明细 + CREATE 日志
        long contractId = contractRepository.create(contract);

        // 回写 PO 标记
        poSyncRepository.markContractCreated(poNo, contractId);

        log.info("合同创建成功：poNo={}, contractNo={}, contractId={}", poNo, contractNo, contractId);

        // 首次创建完成即保存 PDF；后续未编辑时签署可直接复用，避免再次拉取图片和填充模板。
        generateInitialContractPdf(contract);
        return true;
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
     * <p>整个方法是同步的，慢在按 SKU 逐个查领星商品图，前端几十秒看不到任何反馈。
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
        if (contractRepository.existsActiveByPurchaseOrderNo(purchaseOrderNo)) {
            throw new RuntimeException("该采购单已存在合同（非取消状态）");
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

        // 8. 从领星API获取并填充商品图片URL（最慢的一步，进度按 SKU 逐个上报）
        enrichContractItemsWithImages(contract, progressKey);

        contractCreateProgressTracker.advance(progressKey, ContractCreateProgressTracker.STEP_SAVE,
                "正在校验必填字段并保存合同");

        // 9. 以最终合同数据做统一必填校验；签署时间允许为空。
        validateManualRequiredFields(contract);

        // 10. 原子保存：合同主表 + 明细 + CREATE 日志
        long contractId = contractRepository.create(contract);

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
     * 从领星API获取商品图片并填充到合同明细中
     *
     * @param contract 合同聚合根
     */
    private void enrichContractItemsWithImages(Contract contract) {
        enrichContractItemsWithImages(contract, null);
    }

    /**
     * 从领星API获取商品图片并填充到合同明细中。
     *
     * <p>这里是创建合同最慢的一段（未命中缓存时每个 SKU 一次领星请求），
     * 所以按明细逐条上报进度（{@code 正在获取商品图片（3/12）}），
     * 让前端能看出是在推进而不是卡死。{@code progressKey} 为 null 时不上报。
     */
    private void enrichContractItemsWithImages(Contract contract, String progressKey) {
        String poNo = contract.getPurchaseOrderNo();
        log.info("开始从领星API获取商品图片：contractNo={}, poNo={}", contract.getContractNo(), poNo);

        try {
            List<ContractItem> contractItems = contract.getItems();
            if (contractItems == null || contractItems.isEmpty()) {
                log.warn("合同无明细，跳过图片获取：contractNo={}", contract.getContractNo());
                return;
            }

            int total = contractItems.size();
            int cacheHitCount = 0;
            int imageFoundCount = 0;
            int imageNotFoundCount = 0;
            int imageErrorCount = 0;

            // 为每个合同明细获取图片URL
            for (int index = 0; index < total; index++) {
                ContractItem contractItem = contractItems.get(index);
                contractCreateProgressTracker.advance(progressKey, ContractCreateProgressTracker.STEP_IMAGES,
                        "正在获取商品图片（" + (index + 1) + "/" + total + "）");
                if (StringUtils.hasText(contractItem.getPicUrl())) {
                    cacheHitCount++;
                    continue;
                }
                String sku = contractItem.getSku();

                if (sku == null || sku.isBlank()) {
                    log.warn("合同明细SKU为空，跳过图片获取：contractNo={}", contract.getContractNo());
                    imageNotFoundCount++;
                    continue;
                }

                Optional<String> cachedPicUrl = contractRepository.findLatestItemPicUrlBySku(sku);
                if (cachedPicUrl.isPresent()) {
                    contractItem.setPicUrl(cachedPicUrl.get());
                    cacheHitCount++;
                    log.debug("复用合同明细图片缓存：sku={}", sku);
                    continue;
                }

                try {
                    log.debug("查询商品详情：sku={}", sku);

                    // 调用领星API查询产品详情
                    Optional<LingxingProductClient.ProductDetail> productOpt =
                            lingxingProductClient.findBySku(sku);

                    if (productOpt.isPresent()) {
                        LingxingProductClient.ProductDetail product = productOpt.get();
                        String picUrl = product.picUrl();

                        if (picUrl != null && !picUrl.isBlank()) {
                            contractItem.setPicUrl(picUrl);
                            imageFoundCount++;
                            log.info("获取商品图片成功：sku={}, picUrl={}", sku, picUrl);
                        } else {
                            log.warn("商品无图片URL：sku={}", sku);
                            imageNotFoundCount++;
                        }
                    } else {
                        log.warn("领星API未找到商品：sku={}", sku);
                        imageNotFoundCount++;
                    }

                } catch (Exception ex) {
                    // 单个商品图片获取失败不影响整体
                    log.error("获取商品图片失败（继续处理其他明细）：sku={}, poNo={}", sku, poNo, ex);
                    imageErrorCount++;
                }
            }

            log.info("合同明细图片处理完成：contractNo={}, 本地缓存={}, 领星查询成功={}, 未找到={}, 失败={}",
                    contract.getContractNo(), cacheHitCount, imageFoundCount, imageNotFoundCount, imageErrorCount);

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
