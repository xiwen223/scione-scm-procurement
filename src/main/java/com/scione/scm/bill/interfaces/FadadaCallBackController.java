package com.scione.scm.bill.interfaces;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.application.BuyerCompanyApplicationService;
import com.scione.scm.bill.config.FadadaOpenApiProperties;
import com.scione.scm.bill.infrastructure.fadada.FadadaRequestSigner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 法大大开放平台事件回调接口（无需登录态，来源合法性由法大大签名保证）。
 *
 * <p>法大大以 {@code application/x-www-form-urlencoded} 形式回调，业务参数放在 {@code bizContent} 字段，
 * 同时把 {@code X-FASC-*} 系列请求头一并带上。验签方式与官方示例一致：取 App-Id / Sign-Type / Timestamp /
 * Nonce / Event 与 bizContent 组成参数集，按 ASCII 排序拼接后做
 * {@code SHA-256 -> HMAC-SHA256(appSecret, timestamp) -> HMAC-SHA256(临时密钥, signText)}，与
 * {@code X-FASC-Sign} 比对。该算法已由 {@link FadadaRequestSigner} 实现，此处直接复用，
 * 避免与出站请求（{@code FadadaOpenApiClient}）出现两套签名实现。</p>
 *
 * <p>响应约定：无论验签成功与否，响应体一律为 {@code {"msg":"success"}}。验签失败时同样返回 success 是
 * 官方建议 —— 返回 success 后法大大将中断该条消息的重试机制，避免伪造请求或密钥不一致导致死循环重试；
 * 失败原因只落服务端日志（不记录签名、AppSecret 等敏感值）。</p>
 *
 * <p>当前处理的事件：</p>
 * <ul>
 *   <li>{@code seal-verify-successed}（印章审核通过）：取 {@code openCorpId} 与 {@code sealId}，
 *       把 {@code fadada_seal_id} 与 {@code seal_flow_status = 1} 写入对应公司；</li>
 *   <li>{@code seal-verify-failed}（印章审核不通过）：取 {@code openCorpId} 与 {@code reason}，
 *       把 {@code seal_flow_status = 2} 与 {@code seal_failed_reason = reason} 写入对应公司；</li>
 *   <li>其余事件（{@code seal-create} / {@code seal-disable} / {@code seal-delete} / {@code notifyUrlVerify} 等）
 *       暂只记录日志，按需在此扩展。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/fadada")
@Tag(name = "法大大回调", description = "接收法大大开放平台事件通知（印章审核结果等）")
public class FadadaCallBackController {

    /** 印章审核通过事件。 */
    private static final String EVENT_SEAL_VERIFY_SUCCESS = "seal-verify-successed";

    /** 印章审核不通过事件。 */
    private static final String EVENT_SEAL_VERIFY_FAILED = "seal-verify-failed";

    /** 回调验签固定使用 HMAC-SHA256，与官方示例一致（不取请求头的实际值做二次信任）。 */
    private static final String SIGN_TYPE = "HMAC-SHA256";

    /** 回调应答体：固定 success，用于中断法大大重试。 */
    private static final String CALLBACK_SUCCESS = "{\"msg\":\"success\"}";

    private final FadadaRequestSigner fadadaRequestSigner;
    private final FadadaOpenApiProperties fadadaOpenApiProperties;
    private final BuyerCompanyApplicationService buyerCompanyApplicationService;
    private final ObjectMapper objectMapper;

    /**
     * 法大大事件回调入口。
     *
     * @param headers   法大大回调请求头，含 X-FASC-App-Id / Sign-Type / Sign / Timestamp / Event / Nonce
     * @param bizContent 业务报文（JSON 字符串），事件不同结构不同
     * @return 固定 {@code {"msg":"success"}}
     */
    @PostMapping("/callback")
    @Operation(summary = "法大大事件回调",
            description = "接收法大大事件通知并按 X-FASC-Event 分发；验签不通过亦返回 success 以中断重试")
    public String callback(@RequestHeader HttpHeaders headers, @RequestParam("bizContent") String bizContent) throws InterruptedException {
        log.info("回调开始！");
        String event = headers.getFirst("X-FASC-Event");

        if (!verifySignature(headers, bizContent)) {
            return CALLBACK_SUCCESS;
        }

        switch (event == null ? "" : event) {
            case EVENT_SEAL_VERIFY_SUCCESS -> handleSealVerifySuccess(bizContent);
            case EVENT_SEAL_VERIFY_FAILED -> handleSealVerifyFailed(bizContent);
            default -> log.info("收到法大大未处理事件：event={}", event);
        }
        return CALLBACK_SUCCESS;
    }

    /**
     * 校验回调签名。参数组装顺序与取值方式完全对齐法大大官方示例，
     * 其中 {@code X-FASC-Sign-Type} 固定取 HMAC-SHA256。
     */
    private boolean verifySignature(HttpHeaders headers, String bizContent) {
        String appId = headers.getFirst("X-FASC-App-Id");
        String signType = headers.getFirst("X-FASC-Sign-Type");
        String sign = headers.getFirst("X-FASC-Sign");
        String timestamp = headers.getFirst("X-FASC-Timestamp");
        String event = headers.getFirst("X-FASC-Event");
        String nonce = headers.getFirst("X-FASC-Nonce");

        if (isBlank(sign) || isBlank(timestamp)) {
            log.warn("法大大回调缺少签名或时间戳，已忽略：event={}", event);
            return false;
        }
        if (!isBlank(signType) && !SIGN_TYPE.equalsIgnoreCase(signType)) {
            log.warn("法大大回调签名类型不支持，已忽略：signType={}", signType);
            return false;
        }

        // 参数排序由签名器内部按 key 的 ASCII 序完成（TreeMap），此处只负责按官方示例组装参数
        Map<String, String> paramMap = new LinkedHashMap<>();
        paramMap.put("X-FASC-App-Id", appId);
        paramMap.put("X-FASC-Sign-Type", SIGN_TYPE);
        paramMap.put("X-FASC-Timestamp", timestamp);
        paramMap.put("X-FASC-Nonce", nonce);
        paramMap.put("X-FASC-Event", event);
        paramMap.put("bizContent", bizContent);

        String expected;
        try {
            expected = fadadaRequestSigner.sign(paramMap, timestamp, fadadaOpenApiProperties.getAppSecret());
        } catch (IllegalStateException exception) {
            // AppSecret 未配置时无法验签；按官方建议返回 success 中断重试，靠日志暴露配置问题
            log.error("法大大回调验签失败：AppSecret 未配置或不可用，event={}", event);
            return false;
        }

        if (!expected.equalsIgnoreCase(sign.trim())) {
            log.warn("法大大回调验签不通过，已忽略：event={}, appId={}", event, appId);
            return false;
        }
        return true;
    }

    /**
     * 印章审核通过：从 bizContent 取 openCorpId / sealId，写回对应公司的印章信息。
     *
     * <p>业务字段优先取 bizContent 顶层；若顶层没有则回落到 {@code data} 节点，兼容业务字段被包一层的报文结构。
     * 字段缺失时只记日志、不抛异常 —— 报文结构问题重试也无法修复，且返回非 success 会导致法大大反复重推。</p>
     */
    private void handleSealVerifySuccess(String bizContent) throws InterruptedException {
        JsonNode payload = parseBizContent(bizContent);
        if (payload == null) {
            return;
        }
        JsonNode business = businessNode(payload);

        String openCorpId = text(business, "openCorpId");
        String sealId = text(business, "sealId");
        if (isBlank(openCorpId) || isBlank(sealId)) {
            log.error("法大大印章审核通过回调缺少 openCorpId 或 sealId，无法更新公司印章：event={}", EVENT_SEAL_VERIFY_SUCCESS);
            return;
        }

        buyerCompanyApplicationService.handleSealVerifySuccess(openCorpId, sealId);
    }

    /**
     * 印章审核不通过：从 bizContent 取 openCorpId 与 reason，把公司印章置为审核失败并记录不通过原因。
     *
     * <p>{@code reason} 允许为空 —— 为空时只更新审核状态，详情页不展示原因。
     * openCorpId 缺失时只记日志、不抛异常：报文结构问题重试无法修复，且返回非 success 会导致法大大反复重推。</p>
     */
    private void handleSealVerifyFailed(String bizContent) throws InterruptedException {
        JsonNode payload = parseBizContent(bizContent);
        if (payload == null) {
            return;
        }
        JsonNode business = businessNode(payload);

        String openCorpId = text(business, "openCorpId");
        if (isBlank(openCorpId)) {
            log.error("法大大印章审核不通过回调缺少 openCorpId，无法更新公司印章：event={}", EVENT_SEAL_VERIFY_FAILED);
            return;
        }

        String reason = text(business, "reason");
        if (isBlank(reason)) {
            log.warn("法大大印章审核不通过回调未携带 reason，仅更新审核状态：openCorpId={}", openCorpId);
        }
        buyerCompanyApplicationService.handleSealVerifyFailed(openCorpId, reason);
    }

    /**
     * 取业务字段所在节点：优先 bizContent 的 {@code data} 子节点，缺失时用顶层，
     * 兼容「业务字段被包一层」与「业务字段平铺」两种报文结构。
     */
    private static JsonNode businessNode(JsonNode payload) {
        return payload.hasNonNull("data") && payload.get("data").isObject() ? payload.get("data") : payload;
    }

    /** 解析 bizContent；报文非法时记日志并返回 null（不打印原始报文，避免泄露业务数据）。 */
    private JsonNode parseBizContent(String bizContent) {
        if (isBlank(bizContent)) {
            log.error("法大大回调 bizContent 为空，无法处理");
            return null;
        }
        try {
            return objectMapper.readTree(bizContent);
        } catch (JsonProcessingException exception) {
            log.error("法大大回调 bizContent 解析失败：{}", exception.getOriginalMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
