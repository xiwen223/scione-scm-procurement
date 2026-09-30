package com.scione.scm.bill.application;

import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.application.dto.ContractCancelRequest;
import com.scione.scm.bill.application.port.ContractFileStore;
import com.scione.scm.bill.application.port.ContractPdfConverter;
import com.scione.scm.bill.application.port.ContractTemplateService;
import com.scione.scm.bill.application.port.LingxingSupplierClient;
import com.scione.scm.bill.config.FadadaOpenApiProperties;
import com.scione.scm.bill.domain.company.BuyerCompany;
import com.scione.scm.bill.domain.company.BuyerCompanyRepository;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.contract.PurchasePriceCalculator;
import com.scione.scm.bill.domain.contract.ContractStatus;
import com.scione.scm.bill.domain.posync.PoSyncRecord;
import com.scione.scm.bill.domain.posync.PoSyncRecordItem;
import com.scione.scm.bill.domain.posync.PoSyncRepository;
import com.scione.scm.bill.infrastructure.fadada.FadadaOpenApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 合同签署阶段操作。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContractSignAppService {


    private final ContractRepository contractRepository;
    private final BuyerCompanyRepository buyerCompanyRepository;
    private final PoSyncRepository poSyncRepository;
    private final LingxingSupplierClient lingxingSupplierClient;
    private final ContractFileStore contractFileStore;
    private final ContractPdfConverter contractPdfConverter;
    private final ContractTemplateService contractTemplateService;
    private final FadadaOpenApiClient fadadaOpenApiClient;
    private final FadadaOpenApiProperties fadadaProperties;

    public StartSignResult startSign(Long contractId, String operatorEmail, boolean forceConfirm) {
        log.info("开始发起合同签署：contractId={}, forceConfirm={}, operator={}", contractId, forceConfirm, operatorEmail);
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同不存在"));
        if (contract.getStatus() != ContractStatus.CREATED) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅创建状态的合同可发起签署");
        }
        log.info("签署前合同状态校验通过：contractNo={}, status={}", contract.getContractNo(), contract.getStatus());
        BuyerCompany buyer = buyerCompanyRepository.findById(contract.getBuyerCompanyId())
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同关联的需方公司不存在"));
        log.info("签署需方配置：contractNo={}, buyerCompanyId={}, buyerName={}, openCorpIdPresent={}, creditCodePresent={}, sealImagePresent={}, fadadaSealIdPresent={}, fadadaSealId={}, freeSignCodePresent={}, freeSignCode={}, freeSignExpireTime={}",
                contract.getContractNo(), buyer.getId(), buyer.getCompanyName(), StringUtils.hasText(buyer.getOpenCorpId()),
                StringUtils.hasText(buyer.getCreditCode()),
                StringUtils.hasText(buyer.getSealUrl()), StringUtils.hasText(buyer.getFadadaSealId()), mask(buyer.getFadadaSealId()),
                StringUtils.hasText(buyer.getFadadaFreeSignBusinessId()), mask(buyer.getFadadaFreeSignBusinessId()), buyer.getFadadaFreeSignExpireTime());
        // 免验证签场景码是法大大建任务的必填项：缺失/过期时必须在任何外部调用之前拦下来，
        // 否则后续领星复核、PDF 生成、文件上传都会白跑一遍，最后仍以法大大报错收场。
        assertFreeSignBusinessIdConfigured(contract, buyer, operatorEmail);
        boolean manualContract = contract.getCreateType() != null
                && contract.getCreateType() == Contract.CREATE_TYPE_MANUAL;
        PoSyncRecord latestPo = null;
        LingxingSupplierClient.SupplierProfile latestSupplier = null;
        String signingSupplierPhone;
        String signingSupplierCreditCode;
        if (manualContract) {
            // 手动合同以保存的合同内容作为法大大签署数据源；领星仅用于差异提示。
            signingSupplierPhone = contract.getSupplierPhone();
            signingSupplierCreditCode = contract.getSupplierCreditCode();
            latestPo = poSyncRepository.findByPurchaseOrderNo(contract.getPurchaseOrderNo()).orElse(null);
            if (contract.getSupplierId() == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "合同缺少供应商ID，无法完成领星复核，请重新查询后再发起签署");
            }
            try {
                latestSupplier = lingxingSupplierClient.findSupplierProfile(contract.getSupplierId()).orElse(null);
            } catch (RuntimeException exception) {
                log.error("手动合同签署前领星供应商复核失败：contractNo={}, supplierId={}",
                        contract.getContractNo(), contract.getSupplierId(), exception);
                throw new BusinessException(ResultCode.LINGXING_API_ERROR,
                        "领星供应商信息查询失败，请重新查询后再发起签署");
            }
            log.info("手动合同签署使用合同保存的供方信息：contractNo={}", contract.getContractNo());
        } else {
            latestPo = poSyncRepository.findByPurchaseOrderNo(contract.getPurchaseOrderNo())
                    .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "领星采购单不存在"));
            latestSupplier = lingxingSupplierClient
                    .findSupplierProfile(contract.getSupplierId())
                    .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "领星供应商不存在"));
            signingSupplierPhone = latestPo.getSupplierPhone();
            signingSupplierCreditCode = latestSupplier.creditCode();
            log.info("自动合同签署使用领星最新供方信息：contractNo={}", contract.getContractNo());
        }
        validateSigningData(contract, buyer, signingSupplierPhone, signingSupplierCreditCode);
        log.info("签署前需方印章与免验证签配置校验通过：contractNo={}, buyerCompanyId={}", contract.getContractNo(), buyer.getId());
        List<String> differences = findLingxingDifferences(contract, latestPo, latestSupplier);
        log.info("领星复核完成：contractNo={}, differenceCount={}", contract.getContractNo(), differences.size());
        if (manualContract && (latestPo == null || latestSupplier == null) && forceConfirm) {
            throw new BusinessException(ResultCode.LINGXING_API_ERROR,
                    "领星采购单或供应商信息未查询到，请重新查询后再发起签署");
        }
        if (!differences.isEmpty() && !forceConfirm) {
            log.warn("合同与领星存在差异，等待用户确认：contractNo={}, differences={}", contract.getContractNo(), differences);
            return new StartSignResult(false, true, differences);
        }

        // 签署前始终依据当前合同数据生成新文件，确保折扣等延迟保存的金额已进入法大大签署文档。
        byte[] fileBytes = loadOrGenerateContractPdfForSigning(contract);
        String fileName = contract.getContractNo() + ".pdf";
        log.info("开始向法大大申请上传地址：contractNo={}, fileName={}, fileType=doc", contract.getContractNo(), fileName);
        FadadaOpenApiClient.UploadUrl upload = fadadaOpenApiClient.getUploadUrl("doc");
        log.info("已获取法大大上传地址：contractNo={}, fddFileUrlPresent={}", contract.getContractNo(), StringUtils.hasText(upload.fddFileUrl()));
        log.info("开始上传合同PDF至法大大：contractNo={}, bytes={}", contract.getContractNo(), fileBytes.length);
        fadadaOpenApiClient.uploadFile(upload.uploadUrl(), fileBytes);
        log.info("合同文件已上传法大大：contractNo={}", contract.getContractNo());
        log.info("开始调用法大大文件处理：contractNo={}, sourceType=doc, targetFormat=pdf", contract.getContractNo());
        FadadaOpenApiClient.ProcessedFile file = fadadaOpenApiClient.processFile(
                upload.fddFileUrl(), fileName, "doc", "pdf");
        log.info("法大大文件处理完成：contractNo={}, fileId={}, pages={}", contract.getContractNo(), file.fileId(), file.fileTotalPages());
        log.info("开始创建法大大签署任务：contractNo={}, buyerSealId={}, freeSignCode={}, supplierName={}, supplierCreditCodePresent={}, supplierPhone={}",
                contract.getContractNo(), mask(buyer.getFadadaSealId()), mask(buyer.getFadadaFreeSignBusinessId()), contract.getSupplierName(),
                StringUtils.hasText(signingSupplierCreditCode), mask(signingSupplierPhone));
        FadadaOpenApiClient.SignTask task = fadadaOpenApiClient.createPurchaseContractTask(
                new FadadaOpenApiClient.PurchaseContractTaskRequest(
                        "采购合同-" + contract.getContractNo(), file.fileId(), contract.getContractNo(),
                        fadadaProperties.getNotifyUrl(), buyer.getCompanyName(), buyer.getCreditCode(), buyer.getOpenCorpId(), buyer.getEntityId(),
                        buyer.getFadadaSealId(), buyer.getFadadaFreeSignBusinessId(),
                        contract.getSupplierName(), signingSupplierCreditCode, signingSupplierPhone, file.fileTotalPages()));
        log.info("法大大双企业签署任务创建成功：contractNo={}, signTaskId={}", contract.getContractNo(), task.signTaskId());
        contractRepository.markSigning(contractId, task.signTaskId());
        log.info("合同状态已更新为签署中：contractNo={}, signTaskId={}", contract.getContractNo(), task.signTaskId());
        logCreatedSignTaskStatus(contract.getContractNo(), task.signTaskId());
        String operator = StringUtils.hasText(operatorEmail) ? operatorEmail : Contract.SYSTEM_OPERATOR;
        contractRepository.saveOperationLog(ContractOperationLog.ofStartSign(
                contractId, contract.getContractNo(), operator, operator, task.signTaskId(),
                differences.isEmpty() ? null : "领星差异=" + String.join(" | ", differences)
                        + "；操作人确认差异后仍发起签署"));
        log.info("发起签署操作日志已写入：contractNo={}, signTaskId={}", contract.getContractNo(), task.signTaskId());
        return new StartSignResult(true, false, differences);
    }

    private List<String> findLingxingDifferences(Contract contract, PoSyncRecord po,
                                                 LingxingSupplierClient.SupplierProfile latestSupplier) {
        List<String> differences = new ArrayList<>();
        if (po == null) {
            differences.add("领星采购单：未查询到");
        } else {
        compare(differences, "供应商名称", contract.getSupplierName(), po.getSupplierName());
        compare(differences, "供应商联系人", contract.getContactPerson(), po.getContactPerson());
        compare(differences, "供应商电话", contract.getSupplierPhone(), po.getSupplierPhone());
        for (ContractItem contractItem : contract.getItems()) {
            PoSyncRecordItem poItem = po.getItems().stream()
                    .filter(item -> contractItem.getSku() != null && contractItem.getSku().equals(item.getSku()))
                    .findFirst().orElse(null);
            if (poItem == null) {
                differences.add("商品SKU=" + contractItem.getSku() + "：合同存在，领星采购单不存在");
                continue;
            }
            String itemPrefix = "商品[" + contractItem.getSku() + "]";
            compare(differences, itemPrefix + "数量", contractItem.getQuantity(), poItem.getQuantityPlan());
            compare(differences, itemPrefix + "单价", contractItem.getUnitPrice(), poItem.getUnitPriceWithoutTax());
            compare(differences, itemPrefix + "金额", contractItem.getAmount(),
                    PurchasePriceCalculator.lineAmount(poItem.getUnitPriceWithoutTax(), poItem.getQuantityPlan()));
            compare(differences, itemPrefix + "预计到货日期", contractItem.getDeliveryDate(), poItem.getExpectArriveTime());
        }
        }
        if (latestSupplier == null) {
            differences.add("领星供应商资料：未查询到");
        } else {
                compare(differences, "供应商地址", contract.getSupplierAddress(), latestSupplier.address());
                compare(differences, "供应商统一社会信用代码", contract.getSupplierCreditCode(), latestSupplier.creditCode());
                latestSupplier.defaultPaymentAccount().ifPresent(account -> {
                    compare(differences, "供应商收款账户名称", contract.getSupplierAccountName(), account.accountName());
                    compare(differences, "供应商银行账号", contract.getSupplierBankAccount(), account.accountId());
                    compare(differences, "供应商开户行", contract.getSupplierBankName(), account.bankName());
                });
        }
        return differences;
    }

    private void compare(List<String> differences, String field, Object contractValue, Object lingxingValue) {
        String left = contractValue == null ? "" : String.valueOf(contractValue).trim();
        String right = lingxingValue == null ? "" : String.valueOf(lingxingValue).trim();
        if (!left.equals(right)) {
            differences.add(field + "：合同[" + left + "]，领星[" + right + "]");
        }
    }

    private byte[] generateLatestContractPdfForSigning(Contract contract) {
        try {
            log.info("签署前生成最新合同文件：contractNo={}, 原价={}, 折扣={}, 实际金额={}",
                    contract.getContractNo(), contract.getOriginalAmount(),
                    contract.getDiscountedAmount(), contract.getContractAmount());
            byte[] pdfBytes = contractPdfConverter.convert(contractTemplateService.fillTemplate(contract),
                    contract.getContractNo());
            String pdfUrl = contractFileStore.store(contract.getContractNo(), pdfBytes, "pdf");
            contractRepository.updatePdfUrl(contract.getId(), pdfUrl);
            contract.setContractPdfUrl(pdfUrl);
            log.info("签署前最新合同文件已上传：contractNo={}, fileUrl={}, bytes={}",
                    contract.getContractNo(), pdfUrl, pdfBytes.length);
            return pdfBytes;
        } catch (Exception ex) {
            log.error("签署前生成最新合同文件失败：contractNo={}", contract.getContractNo(), ex);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "签署前生成最新合同文件失败");
        }
    }

    /**
     * 合同未修改且已有原始 PDF 时直接复用，避免签署前重复填充模板和下载商品图片。
     * 合同编辑会清空 contract_pdf_url，因此仅发生过变更的合同才会走重新生成。
     */
    private byte[] loadOrGenerateContractPdfForSigning(Contract contract) {
        if (StringUtils.hasText(contract.getContractPdfUrl())) {
            try {
                log.info("签署前复用已生成合同PDF：contractNo={}, fileUrl={}",
                        contract.getContractNo(), contract.getContractPdfUrl());
                byte[] cachedPdf = contractFileStore.download(contract.getContractPdfUrl());
                if (cachedPdf != null && cachedPdf.length > 0) {
                    log.info("签署前复用合同PDF成功：contractNo={}, bytes={}", contract.getContractNo(), cachedPdf.length);
                    return cachedPdf;
                }
                log.warn("已保存合同PDF为空，改为重新生成：contractNo={}", contract.getContractNo());
            } catch (Exception ex) {
                log.warn("读取已保存合同PDF失败，改为重新生成：contractNo={}, reason={}",
                        contract.getContractNo(), ex.getMessage());
            }
        }
        return generateLatestContractPdfForSigning(contract);
    }

    public void cancel(Long contractId, ContractCancelRequest request, String operatorEmail) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同不存在"));
        if (contract.getStatus() != ContractStatus.CREATED
                && contract.getStatus() != ContractStatus.SIGNING
                && contract.getStatus() != ContractStatus.EXECUTING) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅创建、签署中或履行中状态的合同可作废");
        }
        String reason = request == null ? null : request.reason();
        if (contract.getStatus() == ContractStatus.SIGNING) {
            if (!StringUtils.hasText(contract.getFadadaTaskId())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "签署中合同缺少法大大任务ID，无法安全作废");
            }
            fadadaOpenApiClient.cancelSignTask(contract.getFadadaTaskId(), reason);
        }
        if (contract.getStatus() == ContractStatus.EXECUTING) {
            if (!StringUtils.hasText(contract.getFadadaTaskId())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "履行中合同缺少法大大任务ID，无法发起作废协议");
            }
            if (!StringUtils.hasText(reason)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "履行中合同作废必须填写作废原因");
            }
            String abolishedTaskId = fadadaOpenApiClient.abolishSignTask(
                    contract.getFadadaTaskId(), fadadaProperties.getOpenCorpId(), reason);
            contractRepository.markFadadaAbolishPending(contractId, abolishedTaskId);
            String operator = StringUtils.hasText(operatorEmail) ? operatorEmail : Contract.SYSTEM_OPERATOR;
            contractRepository.saveOperationLog(ContractOperationLog.ofUpdate(
                    contractId, contract.getContractNo(), operator, operator,
                    "发起合同作废协议",
                    "原签署任务=" + contract.getFadadaTaskId() + "；解除协议任务=" + abolishedTaskId
                            + "；作废原因=" + reason + "；待原签署方完成解除协议后合同才会变更为取消"));
            log.info("履行中合同已发起法大大作废协议：contractNo={}, originalTaskId={}, abolishedTaskId={}",
                    contract.getContractNo(), contract.getFadadaTaskId(), abolishedTaskId);
            return;
        }
        contractRepository.cancel(contractId, reason);
        String operator = StringUtils.hasText(operatorEmail) ? operatorEmail : Contract.SYSTEM_OPERATOR;
        contractRepository.saveOperationLog(ContractOperationLog.ofCancel(
                contractId, contract.getContractNo(), operator, operator, contract.getStatus().getDesc(), reason,
                contract.getFadadaTaskId()));
    }

    public record StartSignResult(boolean started, boolean needConfirm, List<String> differences) {
    }

    /** 查询法大大签署任务状态，用于合同签署联调与页面展示。 */
    public SignTaskStatusResult getSignTaskStatus(Long contractId) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同不存在"));
        if (!StringUtils.hasText(contract.getFadadaTaskId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "合同尚未发起法大大签署任务");
        }
        log.info("查询法大大签署任务状态：contractNo={}, signTaskId={}", contract.getContractNo(), contract.getFadadaTaskId());
        JsonNode detail = fadadaOpenApiClient.getSignTaskDetail(contract.getFadadaTaskId());
        List<SignTaskActorStatus> actors = new ArrayList<>();
        for (JsonNode actor : detail.path("actors")) {
            JsonNode info = actor.path("actorInfo");
            actors.add(new SignTaskActorStatus(
                    info.path("actorId").asText(), info.path("actorName").asText(),
                    actor.path("signOrderNo").asInt(), actor.path("joinStatus").asText(),
                    actor.path("signStatus").asText(), actor.path("signTime").asText(),
                    actor.path("signFields").isArray() && actor.path("signFields").size() > 0
                            ? actor.path("signFields").get(0).path("signFieldStatus").asText() : null));
        }
        return new SignTaskStatusResult(contract.getId(), contract.getContractNo(), contract.getFadadaTaskId(),
                detail.path("signTaskStatus").asText(), List.copyOf(actors));
    }

    public record SignTaskStatusResult(Long contractId, String contractNo, String signTaskId,
                                       String signTaskStatus, List<SignTaskActorStatus> actors) { }

    public record SignTaskActorStatus(String actorId, String actorName, int signOrderNo,
                                      String joinStatus, String signStatus, String signTime,
                                      String signFieldStatus) { }

    /**
     * 回调未送达时的兜底状态同步：仅当法大大任务已完成时才将合同更新为履行中。
     */
    public SignTaskSyncResult syncFinishedSignTask(Long contractId, String operatorEmail) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同不存在"));
        if (!StringUtils.hasText(contract.getFadadaTaskId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "合同尚未发起法大大签署任务");
        }
        JsonNode detail = fadadaOpenApiClient.getSignTaskDetail(contract.getFadadaTaskId());
        String taskStatus = detail.path("signTaskStatus").asText();
        boolean finished = "task_finished".equalsIgnoreCase(taskStatus);
        boolean updated = false;
        if (finished && contract.getStatus() == ContractStatus.SIGNING) {
            contractRepository.markExecuting(contract.getId());
            String operator = StringUtils.hasText(operatorEmail) ? operatorEmail : Contract.SYSTEM_OPERATOR;
            contractRepository.saveOperationLog(ContractOperationLog.ofUpdate(
                    contract.getId(), contract.getContractNo(), operator, operator,
                    "签署状态兜底同步", "法大大任务状态=task_finished；合同状态：签署中 → 履行中"));
            updated = true;
            log.info("法大大签署任务兜底同步完成：contractId={}, contractNo={}, signTaskId={}",
                    contract.getId(), contract.getContractNo(), contract.getFadadaTaskId());
        }
        return new SignTaskSyncResult(contract.getId(), contract.getFadadaTaskId(), taskStatus, updated);
    }

    public record SignTaskSyncResult(Long contractId, String signTaskId, String signTaskStatus, boolean statusUpdated) { }

    /**
     * 发起签署的第一道闸门：校验 buyer_company.fadada_free_sign_business_id。
     *
     * <p>该字段是法大大创建签署任务（免验证签场景码）的必填项，没有值就一定发不起签署。
     * 因此这里在**任何外部调用之前**直接拦截：写 error 日志 + 落一条合同操作日志，
     * 再抛出带专用业务码 {@link ResultCode#CONTRACT_SIGN_FREE_SIGN_NOT_CONFIGURED} 的业务异常，
     * 由接口层回给前端，前端据此弹出提醒弹窗，流程到此为止，不会去发起签署。
     */
    private void assertFreeSignBusinessIdConfigured(Contract contract, BuyerCompany buyer, String operatorEmail) {
        String reason;
        String userMessage;
        if (!StringUtils.hasText(buyer.getFadadaFreeSignBusinessId())) {
            reason = "需方公司[" + buyer.getCompanyName() + "(id=" + buyer.getId()
                    + ")]未配置法大大免验证签场景码：buyer_company.fadada_free_sign_business_id 为空";
            userMessage = "我司未配置免验证签场景码，无法发起签署。"
                    + "请先在需方公司配置中完成法大大免验证签授权，配置完成后再重新发起签署。";
        } else if (buyer.getFadadaFreeSignExpireTime() != null
                && !buyer.getFadadaFreeSignExpireTime().isAfter(LocalDateTime.now())) {
            reason = "需方公司[" + buyer.getCompanyName() + "(id=" + buyer.getId()
                    + ")]免验证签场景码已过期：fadada_free_sign_expire_time=" + buyer.getFadadaFreeSignExpireTime();
            userMessage = "我司免验证签场景码已过期，无法发起签署。"
                    + "请重新完成法大大免验证签授权，授权完成后再重新发起签署。";
        } else {
            return;
        }
        log.error("发起签署被拦截：{}；contractId={}, contractNo={}, buyerCompanyId={}, forceSignSkipped=true",
                reason, contract.getId(), contract.getContractNo(), buyer.getId());
        String operator = StringUtils.hasText(operatorEmail) ? operatorEmail : Contract.SYSTEM_OPERATOR;
        contractRepository.saveOperationLog(ContractOperationLog.ofStartSignBlocked(
                contract.getId(), contract.getContractNo(), operator, operator, reason));
        log.info("发起签署拦截日志已写入：contractId={}, contractNo={}, operationType={}",
                contract.getId(), contract.getContractNo(), ContractOperationLog.TYPE_START_SIGN_BLOCKED);
        throw new BusinessException(ResultCode.CONTRACT_SIGN_FREE_SIGN_NOT_CONFIGURED, userMessage);
    }

    private void validateSigningData(Contract contract, BuyerCompany buyer, String latestSupplierPhone,
                                     String latestSupplierCreditCode) {
        log.info("签署参数校验：contractNo={}, openCorpIdPresent={}, sealImagePresent={}, fadadaSealIdPresent={}, freeSignCodePresent={}, freeSignExpireTime={}, supplierCreditCodePresent={}, supplierPhonePresent={}",
                contract.getContractNo(), StringUtils.hasText(buyer.getOpenCorpId()), StringUtils.hasText(buyer.getSealUrl()), StringUtils.hasText(buyer.getFadadaSealId()),
                StringUtils.hasText(buyer.getFadadaFreeSignBusinessId()), buyer.getFadadaFreeSignExpireTime(),
                StringUtils.hasText(latestSupplierCreditCode), StringUtils.hasText(latestSupplierPhone));
        if (!StringUtils.hasText(buyer.getOpenCorpId())) {
            log.warn("签署参数校验失败：需方法大大OpenCorpId缺失，contractNo={}, buyerCompanyId={}", contract.getContractNo(), buyer.getId());
            throw new BusinessException(ResultCode.PARAM_ERROR, "需方法大大OpenCorpId未配置");
        }
        if (!StringUtils.hasText(buyer.getSealUrl())) {
            log.warn("签署参数校验失败：需方印章图片缺失，contractNo={}, buyerCompanyId={}", contract.getContractNo(), buyer.getId());
            throw new BusinessException(ResultCode.PARAM_ERROR, "我司印章未上传");
        }
        if (!StringUtils.hasText(buyer.getFadadaSealId())) {
            log.warn("签署参数校验失败：法大大印章ID缺失，contractNo={}, buyerCompanyId={}", contract.getContractNo(), buyer.getId());
            throw new BusinessException(ResultCode.PARAM_ERROR, "我司法大大印章未审核完成");
        }
        // 免验证签场景码的「缺失 / 过期」校验已提前到 assertFreeSignBusinessIdConfigured()，
        // 这里不再重复，避免同一条拦截出现两种不同的提示。
        if (!StringUtils.hasText(latestSupplierCreditCode)
                || !StringUtils.hasText(latestSupplierPhone)) {
            log.warn("签署参数校验失败：供方签署资料缺失，contractNo={}, creditCodePresent={}, phonePresent={}",
                    contract.getContractNo(), StringUtils.hasText(latestSupplierCreditCode), StringUtils.hasText(latestSupplierPhone));
            throw new BusinessException(ResultCode.PARAM_ERROR, "供应商统一社会信用代码或签署手机号未填写");
        }
    }

    private String mask(String value) {
        if (!StringUtils.hasText(value)) {
            return "<empty>";
        }
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }

    /** 创建后立即查询一次任务状态，仅用于联调诊断，查询失败不影响已创建任务。 */
    private void logCreatedSignTaskStatus(String contractNo, String signTaskId) {
        try {
            JsonNode detail = fadadaOpenApiClient.getSignTaskDetail(signTaskId);
            JsonNode actors = detail.path("actors");
            StringBuilder actorStatuses = new StringBuilder();
            if (actors.isArray()) {
                for (JsonNode actor : actors) {
                    if (!actorStatuses.isEmpty()) actorStatuses.append("; ");
                    actorStatuses.append(actor.path("actorId").asText("<unknown>"))
                            .append('=').append(actor.path("status").asText("<unknown>"));
                }
            }
            log.info("签署任务创建后状态查询：contractNo={}, signTaskId={}, taskStatus={}, actors={}",
                    contractNo, signTaskId, detail.path("signTaskStatus").asText("<unknown>"), actorStatuses);
        } catch (RuntimeException ex) {
            log.warn("签署任务已创建，但创建后状态查询失败（不影响签署流程）：contractNo={}, signTaskId={}, reason={}",
                    contractNo, signTaskId, ex.getMessage());
        }
    }

    public void urgeSign(Long contractId, String operatorEmail) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同不存在"));
        if (contract.getStatus() != ContractStatus.SIGNING) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅签署中状态的合同可催签");
        }
        if (!StringUtils.hasText(contract.getFadadaTaskId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "合同缺少签署任务信息，无法催办");
        }
        log.info("开始法大大催签：contractNo={}, signTaskId={}", contract.getContractNo(), contract.getFadadaTaskId());
        logSignTaskActorsForUrge(contract.getContractNo(), contract.getFadadaTaskId(), "催签前");
        try {
            fadadaOpenApiClient.urgeSignTask(contract.getFadadaTaskId());
        } catch (BusinessException ex) {
            // 第三方返回内容只留在后台日志；接口响应使用本系统业务文案，避免向用户暴露服务商及业务码。
            log.warn("催办请求未受理：contractNo={}, signTaskId={}, upstreamReason={}",
                    contract.getContractNo(), contract.getFadadaTaskId(), ex.getMessage());
            if (isUrgeTimeLimit(ex.getMessage())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "催办时间间隔未到，请稍后再试");
            }
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "催办暂未成功，请稍后再试");
        }
        log.info("法大大催签接口已成功受理：contractNo={}, signTaskId={}；短信是否送达由法大大平台按任务状态和频控决定",
                contract.getContractNo(), contract.getFadadaTaskId());
        logSignTaskActorsForUrge(contract.getContractNo(), contract.getFadadaTaskId(), "催签后");
        String operator = StringUtils.hasText(operatorEmail) ? operatorEmail : Contract.SYSTEM_OPERATOR;
        contractRepository.saveOperationLog(ContractOperationLog.ofUrgeSign(
                contract.getId(), contract.getContractNo(), operator, operator, contract.getFadadaTaskId()));
    }

    private boolean isUrgeTimeLimit(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("时间")
                || normalized.contains("小时")
                || normalized.contains("分钟")
                || normalized.contains("间隔")
                || normalized.contains("频率")
                || normalized.contains("频繁")
                || normalized.contains("too many")
                || normalized.contains("rate limit");
    }

    private void logSignTaskActorsForUrge(String contractNo, String signTaskId, String scene) {
        try {
            JsonNode detail = fadadaOpenApiClient.getSignTaskDetail(signTaskId);
            StringBuilder actors = new StringBuilder();
            for (JsonNode actor : detail.path("actors")) {
                JsonNode info = actor.path("actorInfo");
                if (!actors.isEmpty()) actors.append("; ");
                actors.append(info.path("actorId").asText("<unknown>"))
                        .append("(name=").append(info.path("actorName").asText("<unknown>"))
                        .append(", joinStatus=").append(actor.path("joinStatus").asText("<unknown>"))
                        .append(", signStatus=").append(actor.path("signStatus").asText("<unknown>"))
                        .append(')');
            }
            log.info("{}法大大任务状态：contractNo={}, signTaskId={}, taskStatus={}, actors={}",
                    scene, contractNo, signTaskId, detail.path("signTaskStatus").asText("<unknown>"), actors);
        } catch (RuntimeException ex) {
            log.warn("{}查询法大大任务状态失败（不影响催签结果）：contractNo={}, signTaskId={}, reason={}",
                    scene, contractNo, signTaskId, ex.getMessage());
        }
    }
}
