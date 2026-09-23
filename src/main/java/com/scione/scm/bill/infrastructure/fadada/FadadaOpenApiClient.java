package com.scione.scm.bill.infrastructure.fadada;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.config.FadadaOpenApiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 法大大 V5 OpenAPI 客户端。
 *
 * <p>迁移自 {@code docs/fadada/fadada_api.py} 中实际使用的 V5 签署任务流程：
 * 获取 token、文件上传处理、创建签署任务、获取签署链接及下载链接。
 * 不使用 README 中已废弃的“创建合同 -> 添加签署方 -> 发起签署”旧接口。</p>
 *
 * <p>签署 URL、下载 URL、accessToken 和签署人实名信息均属于敏感数据，客户端不会写入日志。</p>
 */
@Slf4j
@Component
public class FadadaOpenApiClient {

    private static final String SUCCESS_CODE = "100000";
    private static final String TOKEN_PATH = "/service/get-access-token";
    private static final String GET_UPLOAD_URL_PATH = "/file/get-upload-url";
    private static final String PROCESS_FILE_PATH = "/file/process";
    private static final String CREATE_SIGN_TASK_PATH = "/sign-task/create";
    private static final String START_SIGN_TASK_PATH = "/sign-task/start";
    private static final String GET_ACTOR_URL_PATH = "/sign-task/actor/get-url";
    /** 该查询路径来自本地 Python 示例，供应商标注为旧版；上线前请以租户 V5 文档核验。 */
    private static final String GET_SIGN_TASK_DETAIL_PATH = "/sign-task/app/get-detail";
    private static final String GET_DOWNLOAD_URL_PATH = "/sign-task/owner/get-download-url";
    private static final String GET_CORP_AUTH_URL_PATH = "/corp/get-auth-url";
    private static final String GET_CORP_INFO_PATH = "/corp/get";
    private static final String GET_EDIT_URL_PATH = "/sign-task/get-edit-url";
    private static final String GET_TEMPLATE_DETAIL_PATH = "/sign-template/get-detail";
    private static final String SIGN_TYPE = "HMAC-SHA256";
    private static final int MAX_REASON_LENGTH = 500;

    private final FadadaOpenApiProperties properties;
    private final FadadaRequestSigner signer;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private volatile CachedToken cachedToken;

    public FadadaOpenApiClient(
            FadadaOpenApiProperties properties,
            FadadaRequestSigner signer,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.signer = signer;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = restClientBuilder.clone().requestFactory(requestFactory).build();
    }

    /**
     * 查询企业信息（{@code /corp/get}）。
     *
     * <p>按企业证件号 corpIdentNo 查询，返回响应中的 data 节点，不做字段解析。
     * 业务码非成功时由 {@code businessPost} 抛错；data 缺失或为 null 时返回空 Optional。</p>
     */
    public Optional<JsonNode> getCorp(String corpIdentNo) {
        JsonNode response = businessPost(GET_CORP_INFO_PATH,
                Map.of("corpIdentNo", requireText(corpIdentNo, "corpIdentNo")), true);
        JsonNode data = response.get("data");
        return data == null || data.isNull() ? Optional.empty() : Optional.of(data);
    }

    /** 获取并缓存法大大 accessToken。 */
    public String getAccessToken() {
        ensureConfigured();
        CachedToken current = cachedToken;
        Instant now = Instant.now();
        if (current != null && current.isValidAt(now)) {
            return current.value();
        }
        synchronized (this) {
            current = cachedToken;
            now = Instant.now();
            if (current != null && current.isValidAt(now)) {
                return current.value();
            }
            cachedToken = requestAccessToken(now);
            return cachedToken.value();
        }
    }

    /** 获取预签名上传地址。上传地址本身不得持久化或记录到日志。 */
    public UploadUrl getUploadUrl(String fileType) {
        String effectiveFileType = requireText(fileType, "fileType");
        JsonNode data = businessPost(GET_UPLOAD_URL_PATH, Map.of("fileType", effectiveFileType), true).path("data");
        return new UploadUrl(requiredText(data, "uploadUrl", "获取文件上传地址失败"),
                requiredText(data, "fddFileUrl", "获取文件上传地址失败"));
    }

    /** 使用法大大返回的预签名 URL 上传文件内容。 */
    public void uploadFile(String uploadUrl, byte[] content) {
        if (uploadUrl == null || uploadUrl.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传地址不能为空");
        }
        if (content == null || content.length == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传文件内容不能为空");
        }
        try {
            ResponseEntity<Void> response = restClient.put()
                    .uri(URI.create(uploadUrl))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(content)
                    .retrieve()
                    .toBodilessEntity();
            if (response.getStatusCode().value() != 200) {
                throw fadadaError("文件上传失败：HTTP " + response.getStatusCode().value());
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw fadadaError("文件上传失败：" + transportFailureReason(exception));
        }
    }

    /** 从本地文件读取字节后上传；文件仅在调用期读取，不会写入日志。 */
    public void uploadFile(String uploadUrl, Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "待上传文件不存在");
        }
        try {
            uploadFile(uploadUrl, Files.readAllBytes(file));
        } catch (IOException exception) {
            throw fadadaError("读取待上传文件失败");
        }
    }

    /** 处理已上传文件并获取可用于签署任务的 fileId。 */
    public ProcessedFile processFile(String fddFileUrl, String fileName, String fileType, String fileFormat) {
        String effectiveFileType = requireText(fileType, "fileType");
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("fileType", effectiveFileType);
        file.put("fddFileUrl", requireText(fddFileUrl, "fddFileUrl"));
        file.put("fileName", requireText(fileName, "fileName"));
        if ("doc".equals(effectiveFileType)) {
            file.put("fileFormat", requireText(fileFormat, "fileFormat"));
        }
        JsonNode files = businessPost(PROCESS_FILE_PATH, Map.of("fddFileUrlList", List.of(file)), true)
                .path("data").path("fileIdList");
        if (!files.isArray() || files.isEmpty()) {
            throw fadadaError("文件处理失败：响应中未返回 fileIdList");
        }
        JsonNode first = files.get(0);
        return new ProcessedFile(requiredText(first, "fileId", "文件处理失败"),
                first.path("fileTotalPages").canConvertToInt() ? first.path("fileTotalPages").intValue() : null);
    }

    /** 创建个人签署任务。任务固定自动提交；短信发送取决于 sendNotification。 */
    public SignTask createPersonSignTask(PersonSignTaskRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("actorId", requireText(request.actorId(), "actorId"));
        actor.put("actorType", "person");
        actor.put("actorName", requireText(request.signerName(), "signerName"));
        actor.put("permissions", List.of("sign"));
        actor.put("identNameForMatch", request.signerName());
        actor.put("certType", "id_card");
        actor.put("certNoForMatch", requireText(request.signerIdNo(), "signerIdNo"));
        actor.put("sendNotification", request.sendNotification());
        actor.put("notifyType", List.of("start"));
        actor.put("notifyAddress", requireText(request.signerPhone(), "signerPhone"));
        return createSignTask(request.taskName(), request.fileId(), request.businessNo(), request.notifyUrl(), actor);
    }

    /** 创建企业盖章任务。企业名称、统一社会信用代码和法大大印章 ID 均为必填。 */
    public SignTask createCorpSignTask(CorpSignTaskRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("actorId", requireText(request.actorId(), "actorId"));
        actor.put("actorType", "corp");
        actor.put("actorName", requireText(request.corpName(), "corpName"));
        actor.put("permissions", List.of("sign"));
        actor.put("orgCode", requireText(request.orgCode(), "orgCode"));
        actor.put("sealId", requireText(request.sealId(), "sealId"));
        actor.put("sendNotification", request.sendNotification());
        actor.put("notifyType", List.of("start"));
        actor.put("notifyAddress", requireText(request.notifyPhone(), "notifyPhone"));
        return createSignTask(request.taskName(), request.fileId(), request.businessNo(), request.notifyUrl(), actor);
    }

    /** 对 autoStart=false 的未来场景手动提交签署任务；当前创建任务固定 autoStart=true，通常无需调用。 */
    public void startSignTask(String signTaskId) {
        businessPost(START_SIGN_TASK_PATH, Map.of("signTaskId", requireText(signTaskId, "signTaskId")), false);
    }

    /** 获取参与方签署入口。返回 URL 为短期敏感凭据，调用方不得持久化或写日志。 */
    public ActorSignUrl getActorSignUrl(ActorSignUrlRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signTaskId", requireText(request.signTaskId(), "signTaskId"));
        body.put("actorId", requireText(request.actorId(), "actorId"));
        putIfNotBlank(body, "clientUserId", request.clientUserId());
        putIfNotBlank(body, "redirectUrl", request.redirectUrl());
        putIfNotBlank(body, "redirectMiniAppUrl", request.redirectMiniAppUrl());
        JsonNode data = businessPost(GET_ACTOR_URL_PATH, body, true).path("data");
        String signUrl = text(data, "actorSignTaskUrl");
        String embedUrl = text(data, "actorSignTaskEmbedUrl");
        if (isBlank(signUrl) && isBlank(embedUrl)) {
            throw fadadaError("获取参与方签署链接失败：响应中未返回签署链接");
        }
        return new ActorSignUrl(signUrl, embedUrl);
    }

    /** 查询签署任务详情。保留供应商原始 JSON，避免在 V5 字段未确认前丢失信息。 */
    public JsonNode getSignTaskDetail(String signTaskId) {
        return businessPost(GET_SIGN_TASK_DETAIL_PATH,
                Map.of("signTaskId", requireText(signTaskId, "signTaskId")), true).path("data");
    }

    /** 获取已签署文档下载链接。返回 URL 有效期较短，调用方应即时转发或下载。 */
    public String getSignTaskDownloadUrl(DownloadUrlRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> ownerId = Map.of(
                "idType", requireText(request.ownerIdType(), "ownerIdType"),
                "openId", requireText(request.ownerOpenId(), "ownerOpenId"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownerId", ownerId);
        body.put("signTaskId", requireText(request.signTaskId(), "signTaskId"));
        body.put("compression", request.compression());
        body.put("downloadMode", isBlank(request.downloadMode()) ? "preview" : request.downloadMode());
        putIfNotBlank(body, "customName", request.customName());
        JsonNode data = businessPost(GET_DOWNLOAD_URL_PATH, body, true).path("data");
        return requiredText(data, "downloadUrl", "获取签署文档下载地址失败");
    }

    /** 获取企业授权页面链接。 */
    public String getCorpAuthUrl(String clientCorpId, List<String> authScopes) {
        List<String> scopes = authScopes == null || authScopes.isEmpty()
                ? List.of("signtask_init", "signtask_info") : List.copyOf(authScopes);
        JsonNode data = businessPost(GET_CORP_AUTH_URL_PATH, Map.of(
                "clientCorpId", requireText(clientCorpId, "clientCorpId"), "authScopes", scopes), true).path("data");
        return requiredText(data, "authUrl", "获取企业授权链接失败");
    }

    /** 查询法大大企业绑定、认证和授权信息。 */
    public JsonNode getCorpInfo(String openCorpId, String clientCorpId) {
        if (isBlank(openCorpId) && isBlank(clientCorpId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "openCorpId 和 clientCorpId 至少传入一个");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        putIfNotBlank(body, "openCorpId", openCorpId);
        putIfNotBlank(body, "clientCorpId", clientCorpId);
        return businessPost(GET_CORP_INFO_PATH, body, true).path("data");
    }

    /** 获取签署任务编辑链接。redirectUrl 会按本地 Python 客户端规则进行完整 URL 编码。 */
    public String getSignTaskEditUrl(EditUrlRequest request) {
        Objects.requireNonNull(request, "request");
        boolean hasTaskId = !isBlank(request.signTaskId());
        boolean hasInitiator = !isBlank(request.initiatorIdType()) || !isBlank(request.initiatorOpenId());
        if (hasTaskId == hasInitiator || (!hasTaskId && isBlank(request.initiatorIdType()))) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "signTaskId 与 initiator 必须且只能传入一组");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (hasTaskId) {
            body.put("signTaskId", request.signTaskId());
        } else {
            body.put("initiator", Map.of("idType", requireText(request.initiatorIdType(), "initiatorIdType"),
                    "openId", requireText(request.initiatorOpenId(), "initiatorOpenId")));
        }
        if (!isBlank(request.redirectUrl())) {
            body.put("redirectUrl", encodeRedirectUrl(request.redirectUrl()));
        }
        body.put("editAfterStart", request.editAfterStart());
        JsonNode data = businessPost(GET_EDIT_URL_PATH, body, true).path("data");
        return requiredText(data, "signTaskEditUrl", "获取签署任务编辑链接失败");
    }

    /** 查询签署模板详情，保留供应商原始 JSON。 */
    public JsonNode getSignTemplateDetail(String ownerIdType, String ownerOpenId, String signTemplateId) {
        return businessPost(GET_TEMPLATE_DETAIL_PATH, Map.of(
                "ownerId", Map.of("idType", requireText(ownerIdType, "ownerIdType"),
                        "openId", requireText(ownerOpenId, "ownerOpenId")),
                "signTemplateId", requireText(signTemplateId, "signTemplateId")), true).path("data");
    }

    private SignTask createSignTask(String taskName, String fileId, String businessNo, String notifyUrl,
                                    Map<String, Object> actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signTaskSubject", requireText(taskName, "taskName"));
        body.put("initiator", Map.of("idType", "corp", "openId", configuredOpenCorpId()));
        body.put("autoStart", true);
        body.put("actors", List.of(Map.of("actor", actor)));
        body.put("docs", List.of(Map.of("docId", "doc1", "docName", taskName,
                "docFileId", requireText(fileId, "fileId"))));
        putIfNotBlank(body, "businessNo", businessNo);
        putIfNotBlank(body, "notifyUrl", notifyUrl);
        JsonNode data = businessPost(CREATE_SIGN_TASK_PATH, body, false).path("data");
        return new SignTask(requiredText(data, "signTaskId", "创建签署任务失败"));
    }

    private JsonNode businessPost(String path, Map<String, Object> body, boolean retryable) {
        return signedPost(path, body, getAccessToken(), retryable);
    }

    private CachedToken requestAccessToken(Instant requestedAt) {
        JsonNode response = signedPost(TOKEN_PATH, Map.of(), null, true);
        JsonNode data = response.path("data");
        String token = requiredText(data, "accessToken", "获取 accessToken 失败");
        Duration ttl = properties.getTokenTtl();
        Duration refreshAhead = properties.getTokenRefreshAhead();
        Instant expiresAt = requestedAt.plus(ttl.compareTo(refreshAhead) > 0 ? ttl.minus(refreshAhead) : ttl.dividedBy(2));
        return new CachedToken(token, expiresAt);
    }

    private JsonNode signedPost(String path, Map<String, Object> body, String accessToken, boolean retryable) {
        ensureConfigured();
        final String bizContent = serialize(body);
        String timestamp = Long.toString(Instant.now().toEpochMilli());
        String nonce = Long.toString(System.currentTimeMillis() * 1_000L + (System.nanoTime() % 1_000L));
        Map<String, String> signParameters = new LinkedHashMap<>();
        signParameters.put("X-FASC-App-Id", properties.getAppId());
        signParameters.put("X-FASC-Sign-Type", SIGN_TYPE);
        signParameters.put("X-FASC-Timestamp", timestamp);
        signParameters.put("X-FASC-Nonce", nonce);
        signParameters.put("X-FASC-Api-SubVersion", properties.getApiSubVersion());
        if (accessToken == null) {
            signParameters.put("X-FASC-Grant-Type", "client_credential");
        } else {
            signParameters.put("X-FASC-AccessToken", accessToken);
        }
        if (!bizContent.isEmpty()) {
            signParameters.put("bizContent", bizContent);
        }
        String signature;
        try {
            signature = signer.sign(signParameters, timestamp, properties.getAppSecret());
        } catch (IllegalStateException exception) {
            log.error("Failed to sign Fadada request: {}", sanitizeReason(exception.getMessage()));
            throw fadadaError("请求签名失败");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-FASC-App-Id", properties.getAppId());
        headers.set("X-FASC-Sign-Type", SIGN_TYPE);
        headers.set("X-FASC-Sign", signature);
        headers.set("X-FASC-Timestamp", timestamp);
        headers.set("X-FASC-Nonce", nonce);
        headers.set("X-FASC-Api-SubVersion", properties.getApiSubVersion());
        if (accessToken == null) {
            headers.set("X-FASC-Grant-Type", "client_credential");
        } else {
            headers.set("X-FASC-AccessToken", accessToken);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (!bizContent.isEmpty()) {
            form.add("bizContent", bizContent);
        }

        int attempts = retryable ? Math.max(1, properties.getMaxAttempts()) : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                JsonNode response = restClient.post()
                        .uri(endpoint(path))
                        .headers(requestHeaders -> requestHeaders.addAll(headers))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(JsonNode.class);
                validateResponse(response);
                return response;
            } catch (BusinessException exception) {
                throw exception;
            } catch (RestClientException exception) {
                if (attempt == attempts) {
                    throw fadadaError(transportFailureReason(exception));
                }
                waitBeforeRetry(attempt);
            }
        }
        throw fadadaError("请求失败");
    }

    private void validateResponse(JsonNode response) {
        String code = response == null ? "" : response.path("code").asText();
        if (SUCCESS_CODE.equals(code)) {
            return;
        }
        String message = responseMessage(response);
        String reason = "业务码 " + (code.isBlank() ? "为空" : code)
                + (message.isBlank() ? "" : "：" + message);
        log.warn("Fadada request was rejected: {}", sanitizeReason(reason));
        throw fadadaError(reason);
    }

    private String serialize(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw fadadaError("序列化请求参数失败");
        }
    }

    private void waitBeforeRetry(int attempt) {
        Duration base = properties.getRetryInitialBackoff();
        long delay = Math.max(0L, base.toMillis()) * attempt;
        if (delay == 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw fadadaError("请求被中断");
        }
    }

    private URI endpoint(String path) {
        String base = properties.getEndpoint().toString().replaceAll("/+$", "");
        return URI.create(base + path);
    }

    private void ensureConfigured() {
        if (properties.getEndpoint() == null || isBlank(properties.getAppId()) || isBlank(properties.getAppSecret())) {
            log.error("Fadada OpenAPI credentials are not configured");
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "法大大 OpenAPI 配置不完整");
        }
    }

    private String configuredOpenCorpId() {
        if (isBlank(properties.getOpenCorpId())) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "法大大 OpenAPI 未配置发起企业 openCorpId");
        }
        return properties.getOpenCorpId();
    }

    private String transportFailureReason(RestClientException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            String message = responseMessage(responseException.getResponseBodyAsString());
            String httpReason = "HTTP " + responseException.getStatusCode().value();
            return message.isBlank() ? httpReason : httpReason + "：" + message;
        }
        if (exception instanceof ResourceAccessException) {
            return "网络连接失败或请求超时";
        }
        return "请求失败（" + exception.getClass().getSimpleName() + "）";
    }

    private String responseMessage(String responseBody) {
        if (isBlank(responseBody)) {
            return "";
        }
        try {
            return responseMessage(objectMapper.readTree(responseBody));
        } catch (JsonProcessingException exception) {
            return "";
        }
    }

    private static String responseMessage(JsonNode response) {
        if (response == null) {
            return "";
        }
        for (String field : List.of("msg", "message", "error_description", "error")) {
            String message = response.path(field).asText();
            if (!message.isBlank()) {
                return message;
            }
        }
        return "";
    }

    private String requiredText(JsonNode node, String field, String failureMessage) {
        String value = text(node, field);
        if (isBlank(value)) {
            throw fadadaError(failureMessage + "：响应中未返回 " + field);
        }
        return value;
    }

    private String requireText(String value, String field) {
        if (isBlank(value)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, field + " 不能为空");
        }
        return value.trim();
    }

    private BusinessException fadadaError(String reason) {
        String safeReason = sanitizeReason(reason);
        String message = ResultCode.FADADA_API_ERROR.getMessage()
                + (safeReason.isBlank() ? "" : "：" + safeReason);
        return new BusinessException(ResultCode.FADADA_API_ERROR, message);
    }

    private String sanitizeReason(String reason) {
        if (isBlank(reason)) {
            return "";
        }
        String sanitized = reason.replace('\r', ' ').replace('\n', ' ').trim();
        if (!isBlank(properties.getAppSecret())) {
            sanitized = sanitized.replace(properties.getAppSecret(), "***");
        }
        CachedToken current = cachedToken;
        if (current != null && !isBlank(current.value())) {
            sanitized = sanitized.replace(current.value(), "***");
        }
        return sanitized.length() <= MAX_REASON_LENGTH ? sanitized : sanitized.substring(0, MAX_REASON_LENGTH) + "...";
    }

    private static void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (!isBlank(value)) {
            target.put(key, value.trim());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }



    public record UploadUrl(String uploadUrl, String fddFileUrl) {
    }

    public record ProcessedFile(String fileId, Integer fileTotalPages) {
    }

    public record SignTask(String signTaskId) {
    }

    public record PersonSignTaskRequest(
            String taskName, String fileId, String actorId, String signerPhone, String signerName,
            String signerIdNo, String businessNo, String notifyUrl, boolean sendNotification) {
    }

    public record CorpSignTaskRequest(
            String taskName, String fileId, String actorId, String corpName, String orgCode, String sealId,
            String notifyPhone, String businessNo, String notifyUrl, boolean sendNotification) {
    }

    public record ActorSignUrl(
            String actorSignTaskUrl, String actorSignTaskEmbedUrl) {
    }

    public record ActorSignUrlRequest(
            String signTaskId, String actorId, String clientUserId, String redirectUrl, String redirectMiniAppUrl) {
    }

    public record DownloadUrlRequest(
            String ownerIdType, String ownerOpenId, String signTaskId, String customName,
            boolean compression, String downloadMode) {
    }

    public record EditUrlRequest(
            String signTaskId, String initiatorIdType, String initiatorOpenId,
            String redirectUrl, boolean editAfterStart) {
    }

    private record CachedToken(String value, Instant expiresAt) {
        private boolean isValidAt(Instant instant) {
            return instant.isBefore(expiresAt);
        }
    }

    private static String encodeRedirectUrl(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
