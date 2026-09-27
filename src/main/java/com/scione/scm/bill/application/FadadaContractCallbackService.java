package com.scione.scm.bill.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.config.FadadaOpenApiProperties;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractOperationLog;
import com.scione.scm.bill.domain.contract.ContractRepository;
import com.scione.scm.bill.domain.contract.ContractStatus;
import com.scione.scm.bill.infrastructure.fadada.FadadaRequestSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FadadaContractCallbackService {
    private final FadadaOpenApiProperties properties;
    private final FadadaRequestSigner signer;
    private final ObjectMapper objectMapper;
    private final ContractRepository contractRepository;

    public void handle(Map<String, String> headers, String bizContent) throws Exception {
        String appId = headers.get("X-FASC-App-Id");
        String timestamp = headers.get("X-FASC-Timestamp");
        String signature = headers.get("X-FASC-Sign");
        String event = headers.get("X-FASC-Event");
        if (!properties.getAppId().equals(appId) || signature == null || event == null) throw new IllegalArgumentException("非法法大大回调");
        Map<String, String> params = new LinkedHashMap<>();
        for (String key : new String[]{"X-FASC-App-Id","X-FASC-Sign-Type","X-FASC-Timestamp","X-FASC-Nonce","X-FASC-Event"}) params.put(key, headers.get(key));
        params.put("bizContent", bizContent == null ? "" : bizContent);
        if (!signer.sign(params, timestamp, properties.getAppSecret()).equalsIgnoreCase(signature)) throw new IllegalArgumentException("法大大回调验签失败");
        JsonNode body = businessNode(objectMapper.readTree(bizContent));
        String contractNo = contractNo(body);
        if (contractNo.isBlank()) {
            log.warn("法大大合同回调无法关联合同：event={}, transReferenceIdPresent={}, businessNoPresent={}",
                    event, body.hasNonNull("transReferenceId"), body.hasNonNull("businessNo"));
            return;
        }
        Contract contract = contractRepository.findByContractNo(contractNo).orElse(null);
        if (contract == null) {
            log.warn("法大大合同回调未找到对应合同：event={}, contractNo={}", event, contractNo);
            return;
        }
        log.info("法大大合同回调已关联合同：event={}, contractId={}, contractNo={}, currentStatus={}",
                event, contract.getId(), contractNo, contract.getStatus());
        String details = "法大大事件=" + event + "；任务ID=" + body.path("signTaskId").asText()
                + "；事件时间=" + body.path("eventTime").asText();
        String reason = firstText(body, "signFailedReason", "signRejectReason", "terminationNote", "reason");
        if (!reason.isBlank()) details += "；原因=" + reason;
        if ("sign-task-finished".equals(event) && contract.getStatus() == ContractStatus.SIGNING) {
            contractRepository.markExecuting(contract.getId());
            log.info("法大大签署完成，合同已更新为履行中：contractId={}, contractNo={}", contract.getId(), contractNo);
            log.info("法大大签署完成，合同已更新为履行中：contractId={}, contractNo={}", contract.getId(), contractNo);
            details += "；合同状态：签署中 → 履行中";
        }
        ContractOperationLog log = ContractOperationLog.ofUpdate(contract.getId(), contractNo, "fadada", "法大大回调", "签署任务回调", details);
        contractRepository.saveOperationLog(log);
    }

    /** 已由统一回调控制器完成验签后的合同事件处理。 */
    public void handleVerifiedEvent(String event, String bizContent) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-FASC-App-Id", properties.getAppId());
        headers.put("X-FASC-Timestamp", "0");
        headers.put("X-FASC-Sign", "verified");
        headers.put("X-FASC-Event", event);
        JsonNode body = businessNode(objectMapper.readTree(bizContent));
        String contractNo = contractNo(body);
        if (contractNo.isBlank()) {
            log.warn("法大大合同回调无法关联合同：event={}, transReferenceIdPresent={}, businessNoPresent={}",
                    event, body.hasNonNull("transReferenceId"), body.hasNonNull("businessNo"));
            return;
        }
        Contract contract = contractRepository.findByContractNo(contractNo).orElse(null);
        if (contract == null) {
            log.warn("法大大合同回调未找到对应合同：event={}, contractNo={}", event, contractNo);
            return;
        }
        log.info("法大大合同回调已关联合同：event={}, contractId={}, contractNo={}, currentStatus={}",
                event, contract.getId(), contractNo, contract.getStatus());
        String details = "法大大事件=" + event + "；任务ID=" + body.path("signTaskId").asText()
                + "；事件时间=" + body.path("eventTime").asText();
        String reason = firstText(body, "signFailedReason", "signRejectReason", "terminationNote", "reason");
        if (!reason.isBlank()) details += "；原因=" + reason;
        if ("sign-task-finished".equals(event) && contract.getStatus() == ContractStatus.SIGNING) {
            contractRepository.markExecuting(contract.getId());
            details += "；合同状态：签署中 → 履行中";
        }
        contractRepository.saveOperationLog(ContractOperationLog.ofUpdate(
                contract.getId(), contractNo, "fadada", "法大大回调", "签署任务回调", details));
    }

    private String firstText(JsonNode body, String... fields) {
        for (String field : fields) {
            String value = body.path(field).asText();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static JsonNode businessNode(JsonNode body) {
        return body.hasNonNull("data") && body.path("data").isObject() ? body.path("data") : body;
    }

    private static String contractNo(JsonNode body) {
        String transReferenceId = body.path("transReferenceId").asText();
        return transReferenceId.isBlank() ? body.path("businessNo").asText() : transReferenceId;
    }
}
