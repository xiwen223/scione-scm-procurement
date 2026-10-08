package com.scione.scm.bill.infrastructure.fadada;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.api.data.client.WeComClient;
import com.scione.api.data.req.AlertRequest;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.config.FadadaOpenApiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final String GET_SIGN_TASK_DETAIL_V5_PATH = "/sign-task/get-detail";
    private static final String ADD_SIGN_TASK_FIELDS_PATH = "/sign-task/field/add";
    private static final String GET_SIGN_TASK_FIELDS_PATH = "/sign-task/field/list";
    private static final String MODIFY_SIGN_TASK_ACTORS_PATH = "/sign-task/actor/modify";
    /** 该查询路径来自本地 Python 示例，供应商标注为旧版；上线前请以租户 V5 文档核验。 */
    private static final String GET_SIGN_TASK_DETAIL_PATH = "/sign-task/app/get-detail";
    private static final String GET_DOWNLOAD_URL_PATH = "/sign-task/owner/get-download-url";
    private static final String GET_CORP_AUTH_URL_PATH = "/corp/get-auth-url";
    private static final String GET_CORP_INFO_PATH = "/corp/get";
    private static final String GET_CORP_ENTITY_LIST_PATH = "/corp/entity/get-list";
    private static final String GET_EDIT_URL_PATH = "/sign-task/get-edit-url";
    private static final String GET_TEMPLATE_DETAIL_PATH = "/sign-template/get-detail";
    private static final String CREATE_SEAL_BY_IMAGE_PATH = "/seal/create-by-image";
    private static final String GET_SEAL_FREE_SIGN_URL_PATH = "/seal/free-sign/get-url";
    private static final String SET_SEAL_STATUS_PATH = "/seal/set-status";
    private static final String DELETE_SEAL_PATH = "/seal/delete";
    /** 印章停用状态值，删除印章前先停用。 */
    public static final String SEAL_STATUS_DISABLE = "disable";
    private static final String SIGN_TYPE = "HMAC-SHA256";
    /**
     * 法大大「应用/企业不匹配」家族错误码：遇到这些码时说明当前应用凭据不是任务所属应用，
     * 客户端会换用 {@code fadada.open-api.apps} 中登记的其他应用自动重试一次。
     * 210032 企业用户不存在；211150 发起方或者参与方不匹配；211503 作废发起方信息错误。
     */
    private static final List<String> APP_MISMATCH_CODES = List.of("210032", "211150", "211503");
    private static final int MAX_REASON_LENGTH = 500;
    private static final String CONTRACT_SIGN_DATE_FORMAT = "YYYY-MM-DD";
    /** 法大大日期控件字号单位为 px；13px 最接近合同 HTML 正文默认的 10pt。 */
    private static final int CONTRACT_SIGN_DATE_FONT_SIZE_PX = 13;

    private final FadadaOpenApiProperties properties;
    private final FadadaRequestSigner signer;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    /** 按 appId 缓存 accessToken：不同应用凭据的 token 互不通用。 */
    private final Map<String, CachedToken> cachedTokens = new ConcurrentHashMap<>();
    private final WeComClient weComClient;
    private final ObjectProvider<FadadaOpenApiClient> selfProvider;
    private final FadadaAlertContextHolder contextHolder;

    public FadadaOpenApiClient(
            FadadaOpenApiProperties properties,
            FadadaRequestSigner signer,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            WeComClient weComClient,
            ObjectProvider<FadadaOpenApiClient> selfProvider,
            FadadaAlertContextHolder contextHolder) {
        this.properties = properties;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.weComClient = weComClient;
        this.selfProvider = selfProvider;
        this.contextHolder = contextHolder;
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

    /** 获取并缓存主应用 accessToken。 */
    public String getAccessToken() {
        return accessTokenFor(primaryCredential());
    }

    /** 获取并缓存指定应用凭据的 accessToken。 */
    private String accessTokenFor(Credential credential) {
        ensureCredentialConfigured(credential);
        CachedToken current = cachedTokens.get(credential.appId());
        Instant now = Instant.now();
        if (current != null && current.isValidAt(now)) {
            return current.value();
        }
        synchronized (this) {
            current = cachedTokens.get(credential.appId());
            now = Instant.now();
            if (current != null && current.isValidAt(now)) {
                return current.value();
            }
            CachedToken fresh = requestAccessToken(credential, now);
            cachedTokens.put(credential.appId(), fresh);
            return fresh.value();
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
                String reason = "文件上传失败：HTTP " + response.getStatusCode().value();
                sendFailureAlert("文件上传", reason, null);
                throw fadadaError(reason);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            String reason = "文件上传失败：" + transportFailureReason(exception);
            // 预签名地址含临时凭证，告警仅记录操作名称。
            sendFailureAlert("文件上传", reason, null);
            throw fadadaError(reason);
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
        // 同一个 OpenCorpId 下的子公司需显式指定主体，否则法大大默认使用主企业。
        putIfNotBlank(buyerActor, "actorEntityId", request.buyerEntityId());
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
        // 不传 initiatorEntityId：它必须属于上面的发起企业账号；需方子公司通过 actorEntityId 指定。
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
        // 签订日期取需方实际签署时间，由签署平台写入；不以发起时间代替，也不二次修改已签 PDF。
        docFields.add(dateSignField("buyer-sign-date", "签订日期："));
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
        buyerSignFields.add(signField("buyer-sign-date", null));
        if (needsCrossPageSeal) buyerSignFields.add(signField("buyer-cross-page-seal", request.buyerSealId()));
        List<Map<String, Object>> supplierSignFields = new ArrayList<>();
        supplierSignFields.add(signField("supplier-seal", null));
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
                // 使用与合同其他日期一致的 yyyy-MM-dd 展示。法大大 FieldDateSign 可设格式和字号，未提供字体类型配置。
                "fieldDateSign", Map.of("dateFormat", CONTRACT_SIGN_DATE_FORMAT,
                        "fontSize", CONTRACT_SIGN_DATE_FONT_SIZE_PX),
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
    public String createAbolishSignTask(String signTaskId, String initiatorId, String reason, String businessId,
                                        String buyerActorId, String supplierActorId, String supplierPhone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signTaskId", requireText(signTaskId, "signTaskId"));
        // 平台模板作废协议要先保持未提交，等生成文档并补好签章控件和免验证签配置后再启动。
        body.put("businessId", requireText(businessId, "businessId"));
        body.put("abolishedInitiator", Map.of("initiatorId", requireText(initiatorId, "initiatorId")));
        body.put("docSource", "platform");
        String requiredReason = requireText(reason, "reason");
        if (requiredReason.length() > 200) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "作废原因不能超过200个字符");
        }
        body.put("reason", requiredReason);
        body.put("followOriginalConfig", true);
        body.put("autoStart", false);
        // 法大大文档明确说明：作废任务默认复用原签署方但不发通知。
        // 首次签署通知必须在 /sign-task/abolish 创建请求的 actors.actor 中指定；
        // 创建后再调用 actor/modify 只更新参与方配置，不会可靠触发待签短信。
        Map<String, Object> buyerActor = new LinkedHashMap<>();
        buyerActor.put("actorId", requireText(buyerActorId, "buyerActorId"));
        buyerActor.put("sendNotification", false);
        Map<String, Object> supplierActor = new LinkedHashMap<>();
        supplierActor.put("actorId", requireText(supplierActorId, "supplierActorId"));
        supplierActor.put("notifyAddress", requireText(supplierPhone, "supplierPhone"));
        supplierActor.put("sendNotification", true);
        supplierActor.put("notifyType", List.of("start"));
        body.put("actors", List.of(
                Map.of("actor", buyerActor),
                Map.of("actor", supplierActor)));
        log.info("创建法大大作废协议并配置供方首次签署短信：originalTaskId={}, supplierActorId={}, supplierPhonePresent={}, notifyType=start, followOriginalConfig=true",
                signTaskId, supplierActorId, !isBlank(supplierPhone));
        JsonNode data = businessPost(ABOLISH_SIGN_TASK_PATH, body, false).path("data");
        return requiredText(data, "abolishedSignTaskId", "发起签署任务作废失败");
    }

    /**
     * 查询平台生成的解除协议文档标识。必须在作废任务创建后、添加控件前调用。
     */
    public String getAbolishTaskDocumentId(String signTaskId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signTaskId", requireText(signTaskId, "signTaskId"));
        body.put("filter", List.of("doc"));
        JsonNode docs = businessPost(GET_SIGN_TASK_DETAIL_V5_PATH, body, true).path("data").path("docs");
        if (!docs.isArray() || docs.isEmpty()) {
            throw fadadaError("查询平台生成的解除协议文档失败：响应中没有文档");
        }
        for (JsonNode doc : docs) {
            String docId = text(doc, "docId");
            if (!isBlank(docId)) {
                return docId;
            }
        }
        throw fadadaError("查询平台生成的解除协议文档失败：响应中没有有效的docId");
    }

    /**
     * 在法大大生成的解除协议中添加需方企业印章控件。
     *
     * <p>解除协议是由法大大在 /sign-task/abolish 之后动态生成的文档，不是模板编辑阶段；
     * 因此这里使用 pixel 模式。法大大坐标定义为 96 DPI、页面左上角为原点、签章中心点坐标。
     * 根据用户提供的 A4 已签署解除协议 PDF 中签章控件的 PDF Rect 换算，签章中心约为
     * 第一页 x=289px、y=268px。</p>
     */
    public String addAbolishBuyerSealField(String signTaskId, String docId) {
        String fieldId = "abolish-buyer-seal";
        Map<String, Object> position = Map.of(
                "positionMode", "pixel",
                "positionPageNo", 1,
                "positionX", 289,
                "positionY", 268);
        Map<String, Object> docField = Map.of(
                "fieldId", fieldId,
                "fieldName", fieldId,
                "fieldType", "corp_seal",
                "moveable", false,
                "position", position);
        Map<String, Object> body = Map.of(
                "signTaskId", requireText(signTaskId, "signTaskId"),
                "fields", List.of(Map.of(
                        "docId", requireText(docId, "docId"),
                        "docFields", List.of(docField))));
        // Field.fieldId 由接入方自定义，接口文档没有说添加后会自动改写；
        // 添加接口成功即以本次提交的控件编码关联参与方，避免依赖控件列表的响应层级。
        businessPost(ADD_SIGN_TASK_FIELDS_PATH, body, false);
        return fieldId;
    }

    /** 将需方签章控件关联至作废协议的需方参与方，并开启免验证签。 */
    public void configureAbolishBuyerFreeSign(String signTaskId, String buyerActorId, String docId,
                                               String fieldId, String buyerSealId) {
        Map<String, Object> signField = new LinkedHashMap<>();
        signField.put("fieldDocId", requireText(docId, "docId"));
        signField.put("fieldId", requireText(fieldId, "fieldId"));
        signField.put("sealId", requireText(buyerSealId, "buyerSealId"));
        Map<String, Object> buyerActor = new LinkedHashMap<>();
        buyerActor.put("actorId", requireText(buyerActorId, "buyerActorId"));
        buyerActor.put("signFields", List.of(signField));
        buyerActor.put("signConfigInfo", Map.of("requestVerifyFree", true, "resizeSeal", true));
        // businessId 已在 /abolish 创建任务时设置；法大大规定任务已存在场景码时此处不能重复传。
        businessPost(MODIFY_SIGN_TASK_ACTORS_PATH, Map.of(
                "signTaskId", requireText(signTaskId, "signTaskId"),
                "actors", List.of(buyerActor)), false);
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

    /**
     * 查询企业主体列表（{@code /corp/entity/get-list}，POST）。
     *
     * <p>一个法大大企业账号下可以有多个主体：{@code entityType=primary} 是企业本身，
     * {@code subsidiary} 是成员企业。建章（{@link #createSealByImage}）需要指定印章归属哪个主体，
     * 因此先调本接口取主体清单，再按企业名称挑出 {@code entityId}。只读查询，允许重试。</p>
     *
     * <p>响应 {@code data} 是数组；缺失、非数组或为空数组时一律返回空列表
     * （等价于「该企业下没有可用主体」），由调用方决定如何降级。</p>
     */
    public List<CorpEntity> getCorpEntityList(String openCorpId) {
        JsonNode data = businessPost(GET_CORP_ENTITY_LIST_PATH,
                Map.of("openCorpId", requireText(openCorpId, "openCorpId")), true).path("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return List.of();
        }
        List<CorpEntity> entities = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            entities.add(new CorpEntity(text(item, "entityId"), text(item, "entityType"),
                    text(item, "corpName"), text(item, "corpIdentNo"), text(item, "identStatus")));
        }
        return entities;
    }

    /**
     * 按企业名称在主体列表中定位 {@code entityId}，供建章时指定印章归属主体。
     *
     * <p>取第一个「名称（忽略首尾空白）相同且 entityId 非空」的主体。只有在查到主体却没有同名主体时
     * 才返回 null，此时建章请求<b>不携带 {@code entityId}</b>，沿用「按 openCorpId 归属」的默认行为，
     * 不阻断建章。</p>
     *
     * <p>主体查询本身失败<b>不吞异常</b>：降级建章有可能把印章挂到错误的主体上，属于事后极难察觉的脏数据，
     * 宁可让上传失败（调用方已有补偿逻辑，会清理对象存储与库内签章字段）。</p>
     */
    private String resolveEntityId(String openCorpId, String corpName) {
        if (isBlank(corpName)) {
            log.warn("企业名称为空，跳过法大大主体查询，建章请求不带 entityId");
            return null;
        }
        String target = corpName.trim();
        List<CorpEntity> entities = getCorpEntityList(openCorpId);
        for (CorpEntity entity : entities) {
            if (target.equals(entity.corpName() == null ? null : entity.corpName().trim())
                    && !isBlank(entity.entityId())) {
                return entity.entityId().trim();
            }
        }
        log.warn("法大大主体列表中未找到同名主体，建章请求不带 entityId：corpName={}, 主体数={}", target, entities.size());
        return null;
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
     * <p><b>先查主体、再建章</b>：本方法内部会先按 {@code openCorpId} 调
     * {@link #getCorpEntityList}（{@code /corp/entity/get-list}），用 {@code corpName}
     * 匹配出主体 {@code entityId}，命中时把它作为请求字段传给建章接口，指定印章归属的主体；
     * 未匹配到则不传该字段（行为与旧版一致）。请求体字段顺序为
     * {@code openCorpId → entityId → sealName → sealImage}。</p>
     *
     * <p>印章图片以 Base64 字符串提交（不含 {@code data:} 前缀），调用方负责读取文件字节并编码。
     * 返回 data 中的 {@code verifyId} 表示法大大已受理，印章审核为异步流程；创建类接口不做重试，
     * 避免网络异常时重复建章。</p>
     *
     * <p>{@code verifyId} 是 19 位长整型，因此按 {@code Long} 承载（本地 {@code seal_verify_id} 列同为
     * {@code bigint}）—— 它要在回调里当定位键做等值比较，若以字符串形态落到字符列上，
     * MySQL 会把字符列转成 DOUBLE 再比较，尾数精度不足会让相邻的 verifyId 互相误命中。</p>
     *
     * <p>返回值里把本次解析出的 {@code entityId} 一并带出：它只在建章这一刻由
     * {@link #resolveEntityId} 得到，调用方需要把它和 {@code verifyId} 一起落到
     * {@code buyer_company}，签署阶段才知道这枚印章归属哪个主体。</p>
     *
     * @param openCorpId       法大大企业 ID
     * @param corpName         企业名称，用于在主体列表中匹配 {@code entityId}；为空则跳过主体查询
     * @param sealName         印章名称
     * @param sealImageBase64  印章图片的 Base64 内容
     * @return 建章结果：核验 ID + 本次建章指定的归属主体 ID（未匹配到同名主体时为 {@code null}）
     */
    public SealCreation createSealByImage(String openCorpId, String corpName, String sealName, String sealImageBase64) {
        String corpId = requireText(openCorpId, "openCorpId");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("openCorpId", corpId);
        // 主体只解析这一次：结果既作为建章请求字段，也随返回值交给调用方落库
        String entityId = resolveEntityId(corpId, corpName);
        putIfNotBlank(body, "entityId", entityId);
        body.put("sealName", requireText(sealName, "sealName"));
        body.put("sealImage", requireText(sealImageBase64, "sealImage"));
        JsonNode data = businessPost(CREATE_SEAL_BY_IMAGE_PATH, body, false).path("data");
        return new SealCreation(requiredLong(data, "verifyId", "创建印章失败"), entityId);
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

    /**
     * 调用法大大接口，失败且错误码属于「应用不匹配」时自动换用其他已登记应用重试。
     *
     * <p>历史合同可能由另一套法大大应用签署（法大大的企业与签署任务都挂在应用之下），
     * 此时用当前主应用调用会报 210032 / 211150 / 211503。这些码只表示「用错了应用」，
     * 换成该任务所属应用的凭据即可成功，因此这里按 apps 顺序逐套尝试。</p>
     */
    private JsonNode businessPost(String path, Map<String, Object> body, boolean retryable) {
        List<Credential> credentials = credentialCandidates();
        String lastReason = null;
        for (int index = 0; index < credentials.size(); index++) {
            Credential credential = credentials.get(index);
            JsonNode response = executePost(credential, path, body, accessTokenFor(credential), retryable);
            String code = response == null ? "" : response.path("code").asText();
            if (SUCCESS_CODE.equals(code)) {
                log.info("法大大接口调用成功：path={}, appId={}, code={}", path, credential.appId(), code);
                return response;
            }
            lastReason = businessFailureReason(response);
            boolean hasNext = index + 1 < credentials.size();
            if (hasNext && APP_MISMATCH_CODES.contains(code)) {
                log.warn("法大大应用不匹配（业务码 {}），改用备用应用重试：path={}, 失败appId={}, 备用appId={}",
                        code, path, credential.appId(), credentials.get(index + 1).appId());
                continue;
            }
            log.warn("Fadada request was rejected: {}", sanitizeReason(lastReason));
            sendFailureAlert(path, lastReason, response);
            throw fadadaError(lastReason);
        }
        log.warn("Fadada request was rejected: {}", sanitizeReason(lastReason));
        throw fadadaError(lastReason == null ? "请求失败" : lastReason);
    }

    private String businessFailureReason(JsonNode response) {
        String code = response == null ? "" : response.path("code").asText();
        String message = responseMessage(response);
        return "业务码 " + (code.isBlank() ? "为空" : code) + (message.isBlank() ? "" : "：" + message);
    }

    private CachedToken requestAccessToken(Credential credential, Instant requestedAt) {
        JsonNode response = executePost(credential, TOKEN_PATH, Map.of(), null, true);
        String code = response == null ? "" : response.path("code").asText();
        if (!SUCCESS_CODE.equals(code)) {
            String reason = businessFailureReason(response);
            log.warn("Fadada request was rejected: {}", sanitizeReason(reason));
            sendFailureAlert(TOKEN_PATH, reason, response);
            throw fadadaError(reason);
        }
        JsonNode data = response.path("data");
        String token = requiredText(data, "accessToken", "获取 accessToken 失败");
        Duration ttl = properties.getTokenTtl();
        Duration refreshAhead = properties.getTokenRefreshAhead();
        Instant expiresAt = requestedAt.plus(ttl.compareTo(refreshAhead) > 0 ? ttl.minus(refreshAhead) : ttl.dividedBy(2));
        return new CachedToken(token, expiresAt);
    }

    private JsonNode executePost(Credential credential, String path, Map<String, Object> body,
                                 String accessToken, boolean retryable) {
        ensureCredentialConfigured(credential);
        final String bizContent = serialize(body);
        log.info("法大大接口调用开始：path={}, appId={}, retryable={}, bizContentLength={}",
                path, credential.appId(), retryable, bizContent.length());
        String timestamp = Long.toString(Instant.now().toEpochMilli());
        String nonce = Long.toString(System.currentTimeMillis() * 1_000L + (System.nanoTime() % 1_000L));
        Map<String, String> signParameters = new LinkedHashMap<>();
        signParameters.put("X-FASC-App-Id", credential.appId());
        signParameters.put("X-FASC-Sign-Type", SIGN_TYPE);
        signParameters.put("X-FASC-Timestamp", timestamp);
        signParameters.put("X-FASC-Nonce", nonce);
        signParameters.put("X-FASC-Api-SubVersion", credential.apiSubVersion());
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
            signature = signer.sign(signParameters, timestamp, credential.appSecret());
        } catch (IllegalStateException exception) {
            log.error("Failed to sign Fadada request: {}", sanitizeReason(exception.getMessage()));
            throw fadadaError("请求签名失败");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-FASC-App-Id", credential.appId());
        headers.set("X-FASC-Sign-Type", SIGN_TYPE);
        headers.set("X-FASC-Sign", signature);
        headers.set("X-FASC-Timestamp", timestamp);
        headers.set("X-FASC-Nonce", nonce);
        headers.set("X-FASC-Api-SubVersion", credential.apiSubVersion());
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
                log.info("法大大接口调用完成：path={}, appId={}, attempt={}, code={}",
                        path, credential.appId(), attempt, response == null ? "<空响应>" : response.path("code").asText());
                return response;
            } catch (RestClientException exception) {
                log.warn("法大大接口网络失败：path={}, appId={}, attempt={}, exception={}",
                        path, credential.appId(), attempt, exception.getClass().getSimpleName());
                if (attempt == attempts) {
                    String reason = transportFailureReason(exception);
                    sendFailureAlert(path, reason, null);
                    throw fadadaError(reason);
                }
                waitBeforeRetry(attempt);
            }
        }
        throw fadadaError("请求失败");
    }

    /** 当前主应用凭据。 */
    private Credential primaryCredential() {
        return new Credential(properties.getAppId(), properties.getAppSecret(), properties.getApiSubVersion());
    }

    /** 候选应用凭据：主应用在前，之后依次是 apps 中登记且 appId 不同的备用应用。 */
    private List<Credential> credentialCandidates() {
        List<Credential> candidates = new ArrayList<>();
        Credential primary = primaryCredential();
        candidates.add(primary);
        List<FadadaOpenApiProperties.AppCredential> apps = properties.getApps();
        if (apps == null || apps.isEmpty()) {
            return candidates;
        }
        for (FadadaOpenApiProperties.AppCredential app : apps) {
            if (app == null || isBlank(app.getAppId()) || isBlank(app.getAppSecret())
                    || app.getAppId().equals(primary.appId())) {
                continue;
            }
            String subVersion = isBlank(app.getApiSubVersion())
                    ? properties.getApiSubVersion() : app.getApiSubVersion();
            candidates.add(new Credential(app.getAppId(), app.getAppSecret(), subVersion));
        }
        return candidates;
    }

    /**
     * 将当前业务上下文与失败原因写入告警请求，再通过 Spring 代理提交发送。
     * <p>在当前线程完成数据快照，避免异步线程读取不到上下文；任务提交失败只记录日志。</p>
     * @param path 法大大接口相对路径
     * @param message 法大大返回的失败原因或网络、HTTP 请求失败原因
     * @param response 完整 JSON 响应，网络请求失败时为空
     */
    private void sendFailureAlert(String path, String message, JsonNode response) {
        AlertRequest request = new AlertRequest();
        request.setBusinessType("法大大接口调用");
        request.setAlertType("接口调用失败");
        request.setLevel("P1");
        request.setTitle("接口'" + path + "'调用失败");
        StringBuilder content = new StringBuilder();
        FadadaAlertContextHolder.Context context = contextHolder.get();
        if (context != null) {
            if (!isBlank(context.companyName())) content.append("公司名称：").append(context.companyName()).append("；");
            if (!isBlank(context.contractNo())) content.append("合同编号：").append(context.contractNo()).append("；");
        }
        content.append("失败原因：").append(message);
        request.setContent(content.toString());
        request.setServiceName("scione-scm-procurement");
        request.setAlarmTime(LocalDateTime.now());
        request.setRawResponse(response == null ? "null" : response.toString());
        try {
            // 从容器获取代理；直接 this 调用不会触发 @Async。
            selfProvider.getObject().sendFailureAlert(path, request);
        } catch (RuntimeException exception) {
            log.warn("法大大接口告警任务提交失败：path={}, exception={}", path,
                    exception.getClass().getSimpleName());
        }
    }

    /**
     * 使用指定线程池调用企业微信告警接口，发送失败只记录日志。
     * <p>必须经 Spring 代理调用才能异步执行；线程池饱和时按 CallerRunsPolicy 执行。</p>
     * @param path 法大大接口相对路径，用于定位发送失败日志
     * @param request 已包含业务信息及原始响应的告警请求
     */
    @Async("fadadaAlertExecutor")
    public void sendFailureAlert(String path, AlertRequest request) {
        try {
            ApiResponse<Void> result = weComClient.send(request);
            if (result == null || !result.isSuccess()) {
                log.warn("法大大接口告警提交失败：path={}, code={}", path,
                        result == null ? null : result.getCode());
            }
        } catch (Exception exception) {
            log.warn("法大大接口告警提交异常：path={}, exception={}", path,
                    exception.getClass().getSimpleName());
        }
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

    private void ensureCredentialConfigured(Credential credential) {
        if (properties.getEndpoint() == null || credential == null
                || isBlank(credential.appId()) || isBlank(credential.appSecret())) {
            // 把缺失的键名写进日志与报错：这类问题几乎都是配置中心里 fadada.open-api 整块没生效
            // （例如 YAML 里 open-api: 那一行被注释掉，键掉到了 fadada.* 下），只报「配置不完整」
            // 时排查要从零开始，非常费时。
            log.error("Fadada OpenAPI credentials are not configured: endpoint={}, appIdPresent={}, appSecretPresent={}",
                    properties.getEndpoint(), credential != null && !isBlank(credential.appId()),
                    credential != null && !isBlank(credential.appSecret()));
            throw new BusinessException(ResultCode.SYSTEM_ERROR,
                    "法大大 OpenAPI 配置不完整：缺少 fadada.open-api.appId / appSecret，请检查配置中心该键是否生效");
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

    /**
     * 取必填的长整型字段。法大大报文里数字型 ID 有时是 JSON number、有时是带引号的字符串，
     * 统一按文本取回再解析，两种形态都能吃下。
     */
    private Long requiredLong(JsonNode node, String field, String failureMessage) {
        String value = text(node, field);
        if (isBlank(value)) {
            throw fadadaError(failureMessage + "：响应中未返回 " + field);
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            throw fadadaError(failureMessage + "：响应中的 " + field + " 不是合法的长整型");
        }
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
        for (String secret : knownSecrets()) {
            sanitized = sanitized.replace(secret, "***");
        }
        for (CachedToken token : cachedTokens.values()) {
            if (token != null && !isBlank(token.value())) {
                sanitized = sanitized.replace(token.value(), "***");
            }
        }
        return sanitized.length() <= MAX_REASON_LENGTH ? sanitized : sanitized.substring(0, MAX_REASON_LENGTH) + "...";
    }

    /** 主应用与全部备用应用的 appSecret，用于日志脱敏。 */
    private List<String> knownSecrets() {
        List<String> secrets = new ArrayList<>();
        if (!isBlank(properties.getAppSecret())) {
            secrets.add(properties.getAppSecret());
        }
        if (properties.getApps() != null) {
            for (FadadaOpenApiProperties.AppCredential app : properties.getApps()) {
                if (app != null && !isBlank(app.getAppSecret())) {
                    secrets.add(app.getAppSecret());
                }
            }
        }
        return secrets;
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



    /**
     * 企业主体（{@code /corp/entity/get-list} 的一条记录）。
     *
     * @param entityId    企业主体 ID，建章时作为 {@code entityId} 字段传给 {@code /seal/create-by-image}，
     *                    指定印章归属的主体
     * @param entityType  主体类型：{@code primary} 企业本身，{@code subsidiary} 成员企业
     * @param corpName    主体名称，建章时按它与本地 {@code buyer_company.company_name} 匹配
     * @param corpIdentNo 主体证件号
     * @param identStatus 主体实名认证状态
     */
    public record CorpEntity(String entityId, String entityType, String corpName, String corpIdentNo,
                             String identStatus) {
    }

    /**
     * 建章（{@code /seal/create-by-image}）的返回结果。
     *
     * <p>两个值一起返回是刻意的：{@code entityId} 只在建章这一刻由 {@link #resolveEntityId} 解析得到，
     * 调用方需要把它与 {@code verifyId} 一并落到本地 {@code buyer_company.entity_id}，
     * 签署阶段才知道这枚印章归属哪个主体。</p>
     *
     * @param verifyId 法大大受理创章返回的核验 ID（19 位长整型），印章审核结果回调的定位键
     * @param entityId 本次建章指定的归属主体 ID；未匹配到同名主体时为 {@code null}，
     *                 表示请求未携带该字段、印章按 {@code openCorpId} 默认归属
     */
    public record SealCreation(Long verifyId, String entityId) {
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
            String buyerName, String buyerCreditCode, String buyerOpenCorpId, String buyerEntityId, String buyerSealId, String freeSignBusinessId,
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

    /** 单次调用使用的应用凭据；同一套凭据共享一个 accessToken 缓存。 */
    private record Credential(String appId, String appSecret, String apiSubVersion) {
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
