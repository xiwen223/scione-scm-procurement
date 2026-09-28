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
            if (contract.getSupplierId() != null) {
                latestSupplier = lingxingSupplierClient.findSupplierProfile(contract.getSupplierId()).orElse(null);
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
        if (!differences.isEmpty() && !forceConfirm) {
            log.warn("合同与领星存在差异，等待用户确认：contractNo={}, differences={}", contract.getContractNo(), differences);
            return new StartSignResult(false, true, differences);
        }

        // 签署前始终依据当前合同数据生成新文件，确保折扣等延迟保存的金额已进入法大大签署文档。
        byte[] fileBytes = generateLatestContractPdfForSigning(contract);
        log.info("签署前最新PDF合同已生成：contractNo={}, bytes={}", contract.getContractNo(), fileBytes.length);
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
                        fadadaProperties.getNotifyUrl(), buyer.getCompanyName(), buyer.getCreditCode(), buyer.getOpenCorpId(),
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
            compare(differences, itemPrefix + "单价", contractItem.getUnitPrice(), poItem.getUnitPrice());
            compare(differences, itemPrefix + "金额", contractItem.getAmount(), poItem.getAmount());
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

    public void cancel(Long contractId, ContractCancelRequest request, String operatorEmail) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "合同不存在"));
        if (contract.getStatus() != ContractStatus.CREATED && contract.getStatus() != ContractStatus.SIGNING) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅创建或签署中状态的合同可作废");
        }
        String reason = request == null ? null : request.reason();
        if (contract.getStatus() == ContractStatus.SIGNING) {
            if (!StringUtils.hasText(contract.getFadadaTaskId())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "签署中合同缺少法大大任务ID，无法安全作废");
            }
            fadadaOpenApiClient.cancelSignTask(contract.getFadadaTaskId(), reason);
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
        if (!StringUtils.hasText(buyer.getFadadaFreeSignBusinessId())) {
            log.warn("签署参数校验失败：免验证签场景码缺失，contractNo={}, buyerCompanyId={}", contract.getContractNo(), buyer.getId());
            throw new BusinessException(ResultCode.PARAM_ERROR, "我司未配置免验证签场景码");
        }
        if (buyer.getFadadaFreeSignExpireTime() != null
                && !buyer.getFadadaFreeSignExpireTime().isAfter(LocalDateTime.now())) {
            log.warn("签署参数校验失败：免验证签场景码已过期，contractNo={}, expireTime={}", contract.getContractNo(), buyer.getFadadaFreeSignExpireTime());
            throw new BusinessException(ResultCode.PARAM_ERROR, "我司免验证签场景码已过期");
        }
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
            throw new BusinessException(ResultCode.PARAM_ERROR, "合同未关联法大大签署任务，无法催签");
        }

        fadadaOpenApiClient.urgeSignTask(contract.getFadadaTaskId());
        String operator = StringUtils.hasText(operatorEmail) ? operatorEmail : Contract.SYSTEM_OPERATOR;
        contractRepository.saveOperationLog(ContractOperationLog.ofUrgeSign(
                contract.getId(), contract.getContractNo(), operator, operator, contract.getFadadaTaskId()));
    }
}
