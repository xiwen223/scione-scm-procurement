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
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.contract.ContractStatus;
import com.scione.scm.bill.domain.posync.PoSyncRecord;
import com.scione.scm.bill.domain.posync.PoSyncRepository;
import com.scione.scm.bill.infrastructure.fadada.FadadaOpenApiClient;
import com.scione.scm.bill.infrastructure.fadada.FadadaAlertContext;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    /** 签署前复核与「同步领星数据」弹窗共用同一套字段口径，避免两处算法漂移。 */
    private final ContractLingxingSyncService contractLingxingSyncService;
    private final ContractFileStore contractFileStore;
    private final ContractPdfConverter contractPdfConverter;
    private final ContractTemplateService contractTemplateService;
    private final FadadaOpenApiClient fadadaOpenApiClient;
    private final FadadaOpenApiProperties fadadaProperties;

    @FadadaAlertContext(FadadaAlertContext.Type.CONTRACT)
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
        // 合同创建/编辑时已完成信息回填并持久化；点击开始签署时不再实时查询或复核领星，
        // 统一使用本合同快照，避免签署结果受领星接口状态或 PO 后续变化影响。
        String signingSupplierPhone = contract.getSupplierPhone();
        String signingSupplierCreditCode = contract.getSupplierCreditCode();
        log.info("签署使用合同已保存的供方信息，跳过领星复核：contractNo={}", contract.getContractNo());
        validateSigningData(contract, buyer, signingSupplierPhone, signingSupplierCreditCode);
        log.info("签署前需方印章与免验证签配置校验通过：contractNo={}, buyerCompanyId={}", contract.getContractNo(), buyer.getId());
        List<String> differences = List.of();

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

    /**
     * 签署前领星复核。字段比对（含商品明细全量字段、供方扩展字段、默认收款账户）统一交给
     * {@link ContractLingxingSyncService#describeDifferences}：它取领星实时数据、
     * 价格按「含税单价 ÷ (1 + 税率)」折成不含税后比对，口径与「同步领星数据」弹窗完全一致。
     * 这里只补两行「查不到」的提示，并保持与签署流程一致的阻断语义。
     *
     * <p>复核失败（{@code skippedReason != null}）时不再补「未查询到」，改为补一行失败说明，
     * 并且**不再调用领星**：不可用还继续请求，只会把签署按钮多拖 20 多秒重试。</p>
     */
    private List<String> findLingxingDifferences(Contract contract, PoSyncRecord po,
                                                 LingxingSupplierClient.SupplierProfile latestSupplier,
                                                 String skippedReason) {
        List<String> differences = new ArrayList<>();
        if (skippedReason != null) {
            differences.add(skippedReason);
            return differences;
        }
        if (po == null) {
            differences.add("领星采购单：未查询到");
        }
        if (latestSupplier == null) {
            differences.add("领星供应商资料：未查询到");
        }
        differences.addAll(contractLingxingSyncService.describeDifferences(contract, latestSupplier));
        // 本地快照与领星实时同时查不到时上面会各写一行，去重后再落日志
        return new ArrayList<>(new LinkedHashSet<>(differences));
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

    @FadadaAlertContext(FadadaAlertContext.Type.CONTRACT)
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
            BuyerCompany buyer = buyerCompanyRepository.findById(contract.getBuyerCompanyId())
                    .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同关联的需方公司不存在"));
            String businessId = buyer.getFadadaFreeSignBusinessId();
            if (!StringUtils.hasText(businessId)) {
                throw new BusinessException(ResultCode.CONTRACT_SIGN_FREE_SIGN_NOT_CONFIGURED,
                        "合同关联的需方公司未配置法大大免验证签场景码，无法发起作废协议");
            }
            if (!StringUtils.hasText(buyer.getFadadaSealId())) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "合同关联的需方公司未配置法大大印章，无法发起作废协议");
            }
            if (!StringUtils.hasText(buyer.getCompanyName())) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "合同关联的需方公司名称为空，无法定位解除协议签章控件");
            }
            // 解除协议的发起方必须是原签署任务的发起企业，否则法大大报 211503（非原任务发起方或签署参与方）。
            // 与发起签署时一致，取合同自己需方公司的 openCorpId，不能取全局配置。
            if (!StringUtils.hasText(buyer.getOpenCorpId())) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "合同关联的需方公司未配置法大大企业标识，无法发起作废协议");
            }
            if (buyer.getFadadaFreeSignExpireTime() != null
                    && !buyer.getFadadaFreeSignExpireTime().isAfter(LocalDateTime.now())) {
                throw new BusinessException(ResultCode.CONTRACT_SIGN_FREE_SIGN_NOT_CONFIGURED,
                        "合同关联的需方公司法大大免验证签场景码已过期，无法发起作废协议");
            }
            log.info("履行中合同作废使用合同关联需方公司的免验证签配置：contractNo={}, buyerCompanyId={}, businessIdPresent={}, sealIdPresent={}",
                    contract.getContractNo(), buyer.getId(), StringUtils.hasText(businessId),
                    StringUtils.hasText(buyer.getFadadaSealId()));
            // 用户可以不填写作废原因；法大大解除协议仍需要 reason，因此使用明确的默认原因。
            String trimmedReason = StringUtils.hasText(reason) ? reason.trim() : null;
            String fadadaReason = trimmedReason == null ? "合同作废" : trimmedReason;
            String buyerActorId = "BUYER_" + contract.getContractNo();
            String supplierActorId = "SUPPLIER_" + contract.getContractNo();
            String abolishedTaskId = fadadaOpenApiClient.createAbolishSignTask(
                    contract.getFadadaTaskId(), buyer.getOpenCorpId(), fadadaReason, businessId,
                    buyerActorId, supplierActorId, contract.getSupplierPhone());
            // 先持久化平台任务 ID。后续任一配置步骤失败时，仍可从合同记录和日志定位未提交任务。
            // 作废原因一并落库：这个分支要等法大大回调才会变更为「取消」，回调本身不一定带原因，
            // 详情页要展示用户填的原因，只能在这里先存下来。
            contractRepository.markFadadaAbolishPending(contractId, abolishedTaskId, trimmedReason);
            String operator = StringUtils.hasText(operatorEmail) ? operatorEmail : Contract.SYSTEM_OPERATOR;
            try {
                String generatedDocId = fadadaOpenApiClient.getAbolishTaskDocumentId(abolishedTaskId);
                String buyerSealFieldId = fadadaOpenApiClient.addAbolishBuyerSealField(
                        abolishedTaskId, generatedDocId);
                fadadaOpenApiClient.configureAbolishBuyerFreeSign(
                        abolishedTaskId, buyerActorId, generatedDocId, buyerSealFieldId,
                        buyer.getFadadaSealId());
                log.info("法大大作废协议供方首次签署短信已在创建任务时配置：contractNo={}, abolishedTaskId={}, supplierActorId={}, supplierPhonePresent={}, notificationType=start",
                        contract.getContractNo(), abolishedTaskId, supplierActorId,
                        StringUtils.hasText(contract.getSupplierPhone()));
                fadadaOpenApiClient.startSignTask(abolishedTaskId);
            } catch (RuntimeException ex) {
                String failureDetail = "原签署任务=" + contract.getFadadaTaskId()
                        + "；解除协议任务=" + abolishedTaskId
                        + "；自动配置签章控件或提交任务失败：" + ex.getMessage();
                contractRepository.saveOperationLog(ContractOperationLog.ofUpdate(
                        contractId, contract.getContractNo(), operator, operator,
                        "合同作废协议准备失败", failureDetail));
                log.error("法大大作废协议任务创建后配置失败，任务尚未确认提交：contractNo={}, originalTaskId={}, abolishedTaskId={}",
                        contract.getContractNo(), contract.getFadadaTaskId(), abolishedTaskId, ex);
                throw ex;
            }
            contractRepository.saveOperationLog(ContractOperationLog.ofUpdate(
                    contractId, contract.getContractNo(), operator, operator,
                    "发起合同作废协议",
                    "原签署任务=" + contract.getFadadaTaskId() + "；解除协议任务=" + abolishedTaskId
                            + "；作废原因=" + (StringUtils.hasText(reason) ? reason.trim() : "未填写（法大大解除协议使用默认原因：合同作废）")
                            + "；待原签署方完成解除协议后合同才会变更为取消"));
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
    @FadadaAlertContext(FadadaAlertContext.Type.CONTRACT)
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

    /** 查询法大大解除协议任务状态，用于排查履行中合同作废进度。 */
    public SignTaskStatusResult getAbolishTaskStatus(Long contractId) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同不存在"));
        String taskId = contractRepository.findFadadaAbolishedTaskId(contractId)
                .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR, "合同尚未发起法大大解除协议任务"));
        log.info("查询法大大解除协议任务状态：contractNo={}, abolishedTaskId={}", contract.getContractNo(), taskId);
        JsonNode detail = fadadaOpenApiClient.getSignTaskDetail(taskId);
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
        String taskStatus = detail.path("signTaskStatus").asText();
        log.info("法大大解除协议任务状态查询完成：contractNo={}, abolishedTaskId={}, taskStatus={}, actorCount={}",
                contract.getContractNo(), taskId, taskStatus, actors.size());
        return new SignTaskStatusResult(contract.getId(), contract.getContractNo(), taskId,
                taskStatus, List.copyOf(actors));
    }

    public record SignTaskStatusResult(Long contractId, String contractNo, String signTaskId,
                                       String signTaskStatus, List<SignTaskActorStatus> actors) { }

    public record SignTaskActorStatus(String actorId, String actorName, int signOrderNo,
                                      String joinStatus, String signStatus, String signTime,
                                      String signFieldStatus) { }

    /**
     * 回调未送达时的兜底状态同步：仅当法大大任务已完成时才将合同更新为履行中。
     */
    @FadadaAlertContext(FadadaAlertContext.Type.CONTRACT)
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

    @FadadaAlertContext(FadadaAlertContext.Type.CONTRACT)
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
