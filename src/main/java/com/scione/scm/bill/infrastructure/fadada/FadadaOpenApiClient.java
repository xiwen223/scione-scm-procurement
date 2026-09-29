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
import java.util.ArrayList;
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
    private static final String URGE_SIGN_TASK_PATH = "/sign-task/urge";
    private static final String CANCEL_SIGN_TASK_PATH = "/sign-task/cancel";
    private static final String ABOLISH_SIGN_TASK_PATH = "/sign-task/abolish";
    /** 该查询路径来自本地 Python 示例，供应商标注为旧版；上线前请以租户 V5 文档核验。 */
    private static final String GET_SIGN_TASK_DETAIL_PATH = "/sign-task/app/get-detail";
    private static final String GET_DOWNLOAD_URL_PATH = "/sign-task/owner/get-download-url";
    private static final String GET_CORP_AUTH_URL_PATH = "/corp/get-auth-url";
    private static final String GET_CORP_INFO_PATH = "/corp/get";
    private static final String GET_EDIT_URL_PATH = "/sign-task/get-edit-url";
    private static final String GET_TEMPLATE_DETAIL_PATH = "/sign-template/get-detail";
    private static final String CREATE_SEAL_BY_IMAGE_PATH = "/seal/create-by-image";
    private static final String GET_SEAL_FREE_SIGN_URL_PATH = "/seal/free-sign/get-url";
    private static final String SET_SEAL_STATUS_PATH = "/seal/set-status";
    private static final String DELETE_SEAL_PATH = "/seal/delete";
    /** 印章停用状态值，删除印章前先停用。 */
    public static final String SEAL_STATUS_DISABLE = "disable";
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

    /** 创建采购合同双企业签署任务：我方免验证自动盖章后，再短信通知供应商签署。 */
    public SignTask createPurchaseContractTask(PurchaseContractTaskRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> buyerActor = new LinkedHashMap<>();
        buyerActor.put("actorId", "BUYER_" + request.businessNo());
        buyerActor.put("actorType", "corp");
        buyerActor.put("actorName", requireText(request.buyerName(), "buyerName"));
        buyerActor.put("orgCode", requireText(request.buyerCreditCode(), "buyerCreditCode"));
        // 指定企业印章时，法大大要求参与方同时指定该企业的 actorOpenId/actorFDDId。
        // 当前需方为本集成应用所属企业，复用应用配置的 openCorpId。
        buyerActor.put("actorOpenId", requireText(request.buyerOpenCorpId(), "buyerOpenCorpId"));
        buyerActor.put("permissions", List.of("sign"));
        buyerActor.put("sendNotification", false);

        Map<String, Object> supplierActor = new LinkedHashMap<>();
        supplierActor.put("actorId", "SUPPLIER_" + request.businessNo());
        supplierActor.put("actorType", "corp");
        supplierActor.put("actorName", requireText(request.supplierName(), "supplierName"));
        supplierActor.put("orgCode", requireText(request.supplierCreditCode(), "supplierCreditCode"));
        // 企业参与方的短信/邮件通知地址使用 notifyAddress；accountName 仅对个人参与方有效。
        supplierActor.put("notifyAddress", requireText(request.supplierPhone(), "supplierPhone"));
        supplierActor.put("permissions", List.of("sign"));
        supplierActor.put("sendNotification", true);
        supplierActor.put("notifyType", List.of("start"));

        log.info("法大大采购合同签署任务参数：businessNo={}, buyerOpenCorpId={}, buyerSealId={}, buyerFreeSignEnabled=true, freeSignBusinessId={}, supplierName={}, supplierNotifyEnabled=true, supplierNotifyType=start, supplierNotifyAddress={}",
                request.businessNo(), mask(request.buyerOpenCorpId()), mask(request.buyerSealId()),
                mask(request.freeSignBusinessId()), request.supplierName(), mask(request.supplierPhone()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signTaskSubject", requireText(request.taskName(), "taskName"));
        body.put("signDocType", "contract");
        body.put("initiator", Map.of("idType", "corp", "openId", configuredOpenCorpId()));
        body.put("businessNo", requireText(request.businessNo(), "businessNo"));
        body.put("transReferenceId", requireText(request.businessNo(), "businessNo"));
        body.put("businessId", requireText(request.freeSignBusinessId(), "freeSignBusinessId"));
        body.put("freeSignType", "business");
        body.put("autoStart", true);
        body.put("autoFillFinalize", true);
        body.put("signInOrder", true);
        boolean needsCrossPageSeal = request.fileTotalPages() != null && request.fileTotalPages() > 1;
        List<Map<String, Object>> docFields = new ArrayList<>();
        docFields.add(sealField("buyer-seal", "需方\n单位（盖章）"));
        docFields.add(sealField("supplier-seal", "供方\n单位（盖章）"));
        // 签订日期由最后签署的供方完成签署时写入，避免对已签 PDF 做二次修改而破坏验签。
        docFields.add(dateSignField("supplier-sign-date", "签订日期："));
        if (needsCrossPageSeal) {
            // 骑缝章按法大大规范使用 pixel + positionY；双方使用不同纵向位置，避免控件重叠。
            docFields.add(crossPageSealField("buyer-cross-page-seal", "120"));
            docFields.add(crossPageSealField("supplier-cross-page-seal", "240"));
        }
        body.put("docs", List.of(Map.of(
                "docId", "contract-doc",
                "docName", requireText(request.taskName(), "taskName"),
                "docFileId", requireText(request.fileId(), "fileId"),
                "docFields", docFields)));

        List<Map<String, Object>> buyerSignFields = new ArrayList<>();
        buyerSignFields.add(signField("buyer-seal", request.buyerSealId()));
        if (needsCrossPageSeal) buyerSignFields.add(signField("buyer-cross-page-seal", request.buyerSealId()));
        List<Map<String, Object>> supplierSignFields = new ArrayList<>();
        supplierSignFields.add(signField("supplier-seal", null));
        supplierSignFields.add(signField("supplier-sign-date", null));
        if (needsCrossPageSeal) supplierSignFields.add(signField("supplier-cross-page-seal", null));
        body.put("actors", List.of(
                Map.of("actor", buyerActor,
                        "signFields", buyerSignFields,
                        // 免验证签必须在参与方签署配置中显式开启；任务顶层 businessId 提供场景码。
                        "signConfigInfo", Map.of("orderNo", 1, "resizeSeal", true, "requestVerifyFree", true)),
                Map.of("actor", supplierActor,
                        "signFields", supplierSignFields,
                        "signConfigInfo", Map.of("orderNo", 2, "resizeSeal", true))));
        putIfNotBlank(body, "notifyUrl", request.notifyUrl());
        log.info("调用法大大创建签署任务：businessNo={}, pages={}, crossPageSealEnabled={}, autoStart=true, signInOrder=true, buyerOrder=1, supplierOrder=2, notifyUrlPresent={}",
                request.businessNo(), request.fileTotalPages(), needsCrossPageSeal, !isBlank(request.notifyUrl()));
        JsonNode data = businessPost(CREATE_SIGN_TASK_PATH, body, false).path("data");
        String signTaskId = requiredText(data, "signTaskId", "创建签署任务失败");
        log.info("法大大签署任务创建响应成功：businessNo={}, signTaskId={}", request.businessNo(), signTaskId);
        return new SignTask(signTaskId);
    }

    private Map<String, Object> sealField(String fieldId, String keyword) {
        // FASC V5 企业签章控件枚举值为 corp_seal；seal 不是有效的 fieldType。
        return Map.of("fieldId", fieldId, "fieldName", fieldId, "fieldType", "corp_seal",
                "moveable", false,
                "position", Map.of("positionMode", "keyword", "positionKeyword", keyword,
                        "keywordOffsetX", 0, "keywordOffsetY", 0));
    }

    /**
     * 企业骑缝章使用法大大要求的 pixel 定位模式，仅传纵向坐标；横向位置由平台固定在页面边缘。
     */
    private Map<String, Object> crossPageSealField(String fieldId, String positionY) {
        return Map.of("fieldId", fieldId, "fieldName", fieldId, "fieldType", "corp_seal_cross_page",
                "moveable", false,
                "position", Map.of("positionMode", "pixel", "positionY", positionY));
    }

    /** 签署日期控件，关键字定位在模板“签订日期：”标签右侧。 */
    private Map<String, Object> dateSignField(String fieldId, String keyword) {
        return Map.of("fieldId", fieldId, "fieldName", fieldId, "fieldType", "date_sign",
                "moveable", false,
                "position", Map.of("positionMode", "keyword", "positionKeyword", keyword,
                        // date_sign 按控件中心定位，偏移 100 可使日期文字与相邻值列左侧对齐。
                        "keywordOffsetX", 100, "keywordOffsetY", 0));
    }

    private Map<String, Object> signField(String fieldId, String sealId) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("fieldDocId", "contract-doc");
        field.put("fieldId", fieldId);
        if (!isBlank(sealId)) field.put("sealId", sealId);
        return field;
    }

    /** 对 autoStart=false 的未来场景手动提交签署任务；当前创建任务固定 autoStart=true，通常无需调用。 */
    public void startSignTask(String signTaskId) {
        businessPost(START_SIGN_TASK_PATH, Map.of("signTaskId", requireText(signTaskId, "signTaskId")), false);
    }

    /** 催办待签署参与方；频率限制由法大大平台执行。 */
    public void urgeSignTask(String signTaskId) {
        businessPost(URGE_SIGN_TASK_PATH, Map.of("signTaskId", requireText(signTaskId, "signTaskId")), false);
    }

    /** 撤销尚未结束的签署任务。 */
    public void cancelSignTask(String signTaskId, String terminationNote) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signTaskId", requireText(signTaskId, "signTaskId"));
        putIfNotBlank(body, "terminationNote", terminationNote);
        businessPost(CANCEL_SIGN_TASK_PATH, body, false);
    }

    /**
     * 为已完成的原签署任务发起作废（解除协议）任务。
     * 原任务不会立即作废，需原签署方完成解除协议签署后才会推送 sign-task-abolish 回调。
     */
    public String abolishSignTask(String signTaskId, String initiatorId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signTaskId", requireText(signTaskId, "signTaskId"));
        body.put("abolishedInitiator", Map.of("initiatorId", requireText(initiatorId, "initiatorId")));
        body.put("docSource", "platform");
        body.put("reason", requireText(reason, "reason"));
        body.put("followOriginalConfig", true);
        body.put("autoStart", true);
        JsonNode data = businessPost(ABOLISH_SIGN_TASK_PATH, body, false).path("data");
        return requiredText(data, "abolishedSignTaskId", "发起签署任务作废失败");
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

    /**
     * 通过印章图片创建企业印章（{@code /seal/create-by-image}）。
     *
     * <p>印章图片以 Base64 字符串提交（不含 {@code data:} 前缀），调用方负责读取文件字节并编码。
     * 返回 data 中的 {@code verifyId} 表示法大大已受理，印章审核为异步流程；创建类接口不做重试，
     * 避免网络异常时重复建章。</p>
     */
    public String createSealByImage(String openCorpId, String sealName, String sealImageBase64) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("openCorpId", requireText(openCorpId, "openCorpId"));
        body.put("sealName", requireText(sealName, "sealName"));
        body.put("sealImage", requireText(sealImageBase64, "sealImage"));
        JsonNode data = businessPost(CREATE_SEAL_BY_IMAGE_PATH, body, false).path("data");
        return requiredText(data, "verifyId", "创建印章失败");
    }

    /**
     * 获取企业印章绑定免验证签场景码的授权页面。
     *
     * <p>该接口只生成短期授权链接，不会直接改变印章授权状态；必须由企业超管在法大大页面中确认。
     * 链接属于敏感短期凭据，调用方不得记录或持久化。</p>
     */
    public SealFreeSignUrl getSealFreeSignUrl(SealFreeSignUrlRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("openCorpId", requireText(request.openCorpId(), "openCorpId"));
        body.put("sealIds", List.of(requireText(request.sealId(), "sealId")));
        body.put("businessId", requireText(request.businessId(), "businessId"));
        putIfNotBlank(body, "clientUserId", request.clientUserId());
        putIfNotBlank(body, "redirectUrl", request.redirectUrl());
        JsonNode data = businessPost(GET_SEAL_FREE_SIGN_URL_PATH, body, true).path("data");
        String freeSignUrl = requiredText(data, "freeSignUrl", "获取印章免验证签授权链接失败");
        return new SealFreeSignUrl(freeSignUrl, text(data, "freeSignShortUrl"));
    }

    /**
     * 设置企业印章状态（{@code /seal/set-status}）。
     *
     * <p>{@code sealStatus} 取 {@link #SEAL_STATUS_DISABLE}（停用）等法大大约定值；删除印章前
     * 需要先停用。状态变更类接口不重试，避免网络异常时重复变更。</p>
     */
    public void setSealStatus(String openCorpId, String sealId, String sealStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("openCorpId", requireText(openCorpId, "openCorpId"));
        body.put("sealId", requireText(sealId, "sealId"));
        body.put("sealStatus", requireText(sealStatus, "sealStatus"));
        businessPost(SET_SEAL_STATUS_PATH, body, false);
    }

    /**
     * 删除企业印章（{@code /seal/delete}）。调用前应先通过 {@link #setSealStatus} 停用印章。
     * 删除类接口不重试，避免网络异常时重复删除。
     */
    public void deleteSeal(String openCorpId, String sealId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("openCorpId", requireText(openCorpId, "openCorpId"));
        body.put("sealId", requireText(sealId, "sealId"));
        businessPost(DELETE_SEAL_PATH, body, false);
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
        log.info("法大大接口调用开始：path={}, retryable={}, bizContentLength={}", path, retryable, bizContent.length());
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
                log.info("法大大接口调用成功：path={}, attempt={}, code={}", path, attempt, response.path("code").asText());
                return response;
            } catch (BusinessException exception) {
                log.warn("法大大接口业务失败：path={}, attempt={}, reason={}", path, attempt, sanitizeReason(exception.getMessage()));
                throw exception;
            } catch (RestClientException exception) {
                log.warn("法大大接口网络失败：path={}, attempt={}, exception={}", path, attempt, exception.getClass().getSimpleName());
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

    private static String mask(String value) {
        if (isBlank(value)) {
            return "<empty>";
        }
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
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

    public record PurchaseContractTaskRequest(
            String taskName, String fileId, String businessNo, String notifyUrl,
            String buyerName, String buyerCreditCode, String buyerOpenCorpId, String buyerSealId, String freeSignBusinessId,
            String supplierName, String supplierCreditCode, String supplierPhone, Integer fileTotalPages) {
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

    public record SealFreeSignUrlRequest(
            String openCorpId, String sealId, String businessId, String clientUserId, String redirectUrl) {
    }

    public record SealFreeSignUrl(String freeSignUrl, String freeSignShortUrl) {
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
