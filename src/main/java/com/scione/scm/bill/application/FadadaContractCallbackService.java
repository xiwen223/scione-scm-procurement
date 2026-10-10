package com.scione.scm.bill.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.contract.ContractStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FadadaContractCallbackService {
    private final ObjectMapper objectMapper;
    private final ContractRepository contractRepository;
    private final com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ContractMapper contractMapper;

    /** 已由统一回调控制器完成验签后的合同事件处理。 */
    public void handleVerifiedEvent(String event, String bizContent) throws Exception {
        // 回调步骤1：解析业务内容，兼容外层 data 包装；签名校验已由统一控制器完成。
        JsonNode body = businessNode(objectMapper.readTree(bizContent));
        String contractNo = contractNo(body);
        // 回调步骤2：优先按业务编号查合同，找不到时按原签署或解除协议任务 ID 匹配。
        Contract contract = findContract(body, contractNo);
        if (contract == null) {
            log.info("法大大合同回调无法关联合同：event={}, transReferenceIdPresent={}, businessNoPresent={}",
                    event, body.hasNonNull("transReferenceId"), body.hasNonNull("businessNo"));
            return;
        }
        contractNo = contract.getContractNo();
        // 回调步骤3：我方签完先清除发起标记；合同整体转履行中仍需完整签署任务的完成事件。
        confirmBuyerSign(event, body, contract);
        log.info("法大大合同回调已关联合同：event={}, contractId={}, contractNo={}, currentStatus={}",
                event, contract.getId(), contractNo, contract.getStatus());
        String details = "法大大事件=" + event + "；任务ID=" + body.path("signTaskId").asText()
                + "；事件时间=" + body.path("eventTime").asText();
        String reason = firstText(body, "signFailedReason", "signRejectReason", "terminationNote", "reason");
        if (!reason.isBlank()) details += "；原因=" + reason;
        if (isSignTaskFinished(event, body) && contract.getStatus() == ContractStatus.SIGNING) {
            contractRepository.markExecuting(contract.getId());
            log.info("法大大签署完成，合同已更新为履行中：contractId={}, contractNo={}, event={}, taskStatus={}",
                    contract.getId(), contractNo, event, body.path("signTaskStatus").asText());
            details += "；合同状态：签署中 → 履行中";
        }
        // 解除协议完成事件必须匹配本合同的解除协议任务，不能将原合同完成事件当成作废。
        String callbackTaskId = body.path("signTaskId").asText("");
        boolean abolishAgreementFinished = contract.isAbolishPending()
                && isSignTaskFinished(event, body)
                && !callbackTaskId.isBlank()
                && contractRepository.findFadadaAbolishedTaskId(contract.getId())
                    .filter(callbackTaskId::equals).isPresent();
        if (("sign-task-abolish".equals(event) || abolishAgreementFinished)
                && contract.getStatus() == ContractStatus.EXECUTING) {
            contractRepository.markFadadaAbolished(contract.getId(), reason);
            details += "；解除协议已完成；合同状态：履行中 → 取消";
            log.info("法大大作废协议已完成，合同已更新为取消：contractId={}, contractNo={}", contract.getId(), contractNo);
        }
        // 回调步骤4：记录事件及流转结果，便于排查是否收到回调、匹配到哪份合同。
        contractRepository.saveOperationLog(ContractOperationLog.ofUpdate(
                contract.getId(), contractNo, "fadada", "法大大回调", "签署任务回调", details));
    }

    /** 我方签署完成事件清除子状态，整体任务完成事件仍按原逻辑转履行中。 */
    private void confirmBuyerSign(String event, JsonNode body, Contract contract) {
        String taskId = body.path("signTaskId").asText("");
        String actorId = body.path("actorId").asText(body.path("actorInfo").path("actorId").asText(""));
        boolean buyerSigned = "sign-task-signed".equals(event)
                && ("BUYER_" + contract.getContractNo()).equals(actorId);
        if ((buyerSigned || isSignTaskFinished(event, body)) && !taskId.isBlank()) {
            int updated = contractMapper.confirmSignLaunch(contract.getId(), taskId);
            if (updated > 0) log.info("我方签署回调已确认，清除正在签署子状态：contractNo={}, taskId={}",
                    contract.getContractNo(), taskId);
        }
    }

    private String firstText(JsonNode body, String... fields) {
        for (String field : fields) {
            String value = body.path(field).asText();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    /**
     * 部分法大大任务只推送最后一方的 sign-task-signed，且该事件已经携带 task_finished；
     * 不能只依赖可能未单独推送的 sign-task-finished 事件。
     */
    private boolean isSignTaskFinished(String event, JsonNode body) {
        return "sign-task-finished".equals(event)
                || ("sign-task-signed".equals(event)
                && "task_finished".equals(body.path("signTaskStatus").asText()));
    }

    private static JsonNode businessNode(JsonNode body) {
        return body.hasNonNull("data") && body.path("data").isObject() ? body.path("data") : body;
    }

    private static String contractNo(JsonNode body) {
        String transReferenceId = body.path("transReferenceId").asText();
        return transReferenceId.isBlank() ? body.path("businessNo").asText() : transReferenceId;
    }

    private Contract findContract(JsonNode body, String contractNo) {
        if (contractNo != null && !contractNo.isBlank()) {
            Contract byContractNo = contractRepository.findByContractNo(contractNo).orElse(null);
            if (byContractNo != null) return byContractNo;
        }
        for (String field : new String[]{"signTaskId", "abolishedSignTaskId", "originalSignTaskId",
                "transReferenceId", "businessNo"}) {
            String taskId = body.path(field).asText();
            if (taskId == null || taskId.isBlank()) continue;
            Contract byTaskId = contractRepository.findByFadadaTaskId(taskId).orElse(null);
            if (byTaskId != null) return byTaskId;
        }
        return null;
    }
}
