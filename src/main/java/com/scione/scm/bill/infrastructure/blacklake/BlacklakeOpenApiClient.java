package com.scione.scm.bill.infrastructure.blacklake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ExternalApiResponseException;
import com.scione.scm.bill.common.ExternalApiResponseSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientResponseException;
import com.scione.scm.bill.common.ResultCode;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 黑湖小工单客户端。优先使用 Redis 中的 Token，未命中时读取配置登录并缓存 24 小时。
 * 密码执行 SHA3-224，登录 Cookie 与 Token 随会话传递；仅明确的 Token 解析失败会刷新认证并重试一次，其他失败直接抛出异常。
 */
@Component
@Slf4j
public class BlacklakeOpenApiClient {
    // 登录
    private static final String LOGIN_PATH = "/api/user/v1/users/_login";
    // 查询仓库列表
    private static final String WAREHOUSE_LIST_PATH = "/api/dytin/external/warehouse/queryList";
    // 查询工艺路线
    private static final String PROCESS_ROUTING_LIST_PATH = "/api/dytin/external/processRouting/queryList2";
    private static final String PRODUCT_LIST_PATH = "/api/dytin/external/product/queryList2";
    private static final String SUCCESS_CODE = "01000000";
    private static final String TOKEN_KEY = "scione:scm:blacklake:token";
    private static final Duration TOKEN_TTL = Duration.ofHours(24);
    private final Object tokenRefreshMonitor = new Object();

    @Resource
    private BlacklakeProperties properties;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private BlacklakeRequestLimiter requestLimiter;

    /**
     * 使用 yml 中 blacklake 节点绑定的配置登录，手机号优先，否则使用工厂代码和用户名。
     * 校验配置后执行密码摘要和远端登录，取得 Token 并缓存到 Redis 24 小时，成功后返回独立会话。
     * @return 登录会话；配置缺失、远端登录失败或 Redis 保存失败时抛出业务异常
     */
    public BlacklakeSession login() {
        if (StringUtils.isBlank(properties.getPassword())) {
            throw parameterError("缺少黑湖登录密码");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(properties.getPhone())) {
            payload.put("type", 0);
            payload.put("phone", properties.getPhone());
        } else {
            if (StringUtils.isBlank(properties.getFactoryCode()) || StringUtils.isBlank(properties.getUsername())) {
                throw parameterError("黑湖登录需要手机号或工厂代码和用户名");
            }
            payload.put("type", 1);
            payload.put("code", properties.getFactoryCode());
            payload.put("username", properties.getUsername());
        }
        String password = properties.getPassword();
        if (properties.isEncryptPassword()) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA3-224").digest(password.getBytes(StandardCharsets.UTF_8));
                password = HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("运行环境不支持 SHA3-224", exception);
            }
        }
        payload.put("password", password);
        RestClient restClient = createRestClient();
        JsonNode response = post(restClient, LOGIN_PATH, null, payload);
        if (response.path("statusCode").asInt() != 200) {
            throw responseError("登录失败", response);
        }
        JsonNode token = response.get("data");
        if (token == null || !token.isTextual() || StringUtils.isBlank(token.textValue())) {
            throw responseError("登录响应缺少有效 Token", response);
        }
        try {
            stringRedisTemplate.opsForValue().set(TOKEN_KEY, token.textValue(), TOKEN_TTL);
        } catch (DataAccessException exception) {
            throw remoteError("登录成功，但 Token 保存 Redis 失败");
        }
        return new BlacklakeSession(token.textValue(), restClient);
    }

    /**
     * 按仓库名称模糊查询仓库列表，仅发送 likeName，采用远端默认分页（每页 10 条）。
     * 先读取 Redis Token，未命中才登录并缓存，登录账号须具有管理员权限。
     * @param likeName 仓库名称，不能为空或仅包含空白
     * @return 远端完整 JSON 响应，保留分页及仓库信息，不推算总数
     * @throws BusinessException 参数缺失、认证失效、HTTP 或远端业务处理失败
     */
    public JsonNode queryWarehouses(String likeName) {
        if (StringUtils.isBlank(likeName)) {
            throw parameterError("仓库名称不能为空");
        }
        BlacklakeSession session = getSession();
        JsonNode response = post(session.restClient(), WAREHOUSE_LIST_PATH, session.token(),
                Map.of("likeName", likeName));
        if ("401".equals(response.path("code").asText()) || response.path("statusCode").asInt() == 401) {
            throw responseError("Token 已失效，请重新登录", response);
        }
        if (!SUCCESS_CODE.equals(response.path("code").asText())) {
            throw responseError("仓库列表查询失败", response);
        }
        return response;
    }

    /** 每次调用只查询第一页100条工艺路线，不继续翻页或缓存列表。 */
    public List<ProcessRoutingVO> queryProcessRoutings() {
        BlacklakeSession session = getSession();
        JsonNode response = post(session.restClient(), PROCESS_ROUTING_LIST_PATH, session.token(),
                Map.of("page", Map.of("pageNum", 1, "pageSize", 100)));
        if ("401".equals(response.path("code").asText()) || response.path("statusCode").asInt() == 401) {
            throw responseError("Token 已失效，请重新登录", response);
        }
        if (!SUCCESS_CODE.equals(response.path("code").asText())) {
            throw responseError("工艺路线列表查询失败", response);
        }
        JsonNode routingList = response.path("data").path("data");
        if (!routingList.isArray()) throw responseError("工艺路线列表响应缺少有效data.data数组", response);
        List<ProcessRoutingVO> routings = new ArrayList<>();
        for (JsonNode routing : routingList) {
            JsonNode code = routing.path("processRoutingCode");
            JsonNode name = routing.path("processRoutingName");
            if (!code.isTextual() || code.textValue().isBlank() || !name.isTextual() || name.textValue().isBlank()) {
                throw responseError("工艺路线记录缺少有效processRoutingCode或processRoutingName", response);
            }
            routings.add(new ProcessRoutingVO(code.textValue().trim(), name.textValue().trim()));
        }
        return routings;
    }

    public record ProcessRoutingVO(String processRoutingCode, String processRoutingName) {
    }

    /** 详情/编辑页规范选项；沿用最近一年创建范围，仅发送创建时间上下限。 */
    public List<String> queryInspectionSpecifications() {
        java.time.LocalDateTime end = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("createdAtGte", end.minusYears(1).format(format));
        payload.put("createdAtLte", end.format(format));
        BlacklakeSession session = getSession();
        JsonNode response = post(session.restClient(), "/api/dytin/external/inspectionSpecifications/queryList", session.token(), payload);
        if (!SUCCESS_CODE.equals(response.path("code").asText())) throw responseError("检验规范查询失败", response);
        JsonNode specifications = response.path("data").path("data");
        if (!specifications.isArray()) throw responseError("检验规范响应缺少有效data.data数组", response);
        java.util.Set<String> options = new java.util.LinkedHashSet<>();
        for (JsonNode specification : specifications) {
            JsonNode code = specification.path("code");
            JsonNode name = specification.path("name");
            if (!code.isTextual() || code.asText().isBlank() || !name.isTextual() || name.asText().isBlank()) {
                throw responseError("检验规范缺少有效code或name", response);
            }
            options.add(code.asText().trim() + "-" + name.asText().trim());
        }
        return new ArrayList<>(options);
    }

    /** 仓库名称精确匹配，同名取第一条。 */
    public String findWarehouseCode(String name) {
        String code = queryAllWarehouseCodes().get(name);
        if (code == null) throw remoteError("未找到名称对应的编码：" + name);
        return code;
    }

    /** 按名称查询并读取所有分页；未匹配时返回空映射，留给消费端校验。 */
    public Map<String, String> queryWarehouseCodes(String name) {
        BlacklakeSession session = getSession();
        Map<String, String> codes = new LinkedHashMap<>();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (name != null) payload.put("likeName", name);
        long readCount = 0;
        for (int pageNum = 1; ; pageNum++) {
            payload.put("page", Map.of("pageNum", pageNum, "pageSize", 100));
            JsonNode response = post(session.restClient(), WAREHOUSE_LIST_PATH, session.token(), payload);
            // log.info("/api/dytin/external/warehouse/queryList日志:{}", response);
            if (!SUCCESS_CODE.equals(response.path("code").asText())) throw responseError("字典查询失败：" + WAREHOUSE_LIST_PATH, response);
            JsonNode page = response.path("data");
            JsonNode entries = page.path("data");
            if (!entries.isArray() || !page.path("total").isIntegralNumber() || page.path("total").asLong() < 0) {
                throw responseError("字典查询响应缺少有效data.data或total：" + WAREHOUSE_LIST_PATH, response);
            }
            for (JsonNode entry : entries) {
                JsonNode warehouse = entry.path("simpleVO");
                String warehouseName = warehouse.path("name").asText();
                if (name == null || name.equals(warehouseName)) {
                    JsonNode code = warehouse.path("code");
                    if (!code.isTextual() || code.asText().isBlank()) throw responseError("匹配字典记录缺少有效code：" + name, response);
                    if (warehouseName.isBlank()) throw responseError("仓库记录缺少有效name", response);
                    codes.putIfAbsent(warehouseName, code.asText());
                }
            }
            readCount += entries.size();
            if (readCount >= page.path("total").asLong()) break;
            if (entries.isEmpty()) throw responseError("字典分页提前结束：" + WAREHOUSE_LIST_PATH, response);
        }
        return codes;
    }

    /** 不按名称过滤，统一分页读取全部仓库供本次操作共享。 */
    public Map<String, String> queryAllWarehouseCodes() {
        return queryWarehouseCodes(null);
    }

    /** 按SKU精确查询产品；查询失败不能视为不存在，避免误发新增请求。 */
    public boolean productExists(String sku) {
        if (StringUtils.isBlank(sku)) throw remoteError("查询产品时SKU不能为空");
        BlacklakeSession session = getSession();
        JsonNode response = post(session.restClient(), PRODUCT_LIST_PATH, session.token(),
                Map.of("productCodeExact", sku, "page", Map.of("pageNum", 1, "pageSize", 100)));
        log.info("黑湖产品查询返回，path={}，response={}", PRODUCT_LIST_PATH,
                ExternalApiResponseSanitizer.sanitize(PRODUCT_LIST_PATH, response.toString(), objectMapper));
        if (!SUCCESS_CODE.equals(response.path("code").asText())) throw responseError("查询产品失败", response);
        JsonNode products = response.path("data").path("data");
        if (!products.isArray()) throw responseError("产品查询响应缺少有效data.data数组", response);
        for (JsonNode product : products) {
            JsonNode productCode = product.path("productCode");
            if (!productCode.isTextual() || productCode.asText().isBlank()) {
                throw responseError("产品查询记录缺少有效productCode", response);
            }
            if (!sku.equals(productCode.asText())) {
                throw responseError("产品精确查询返回了不匹配的productCode", response);
            }
        }
        return !products.isEmpty();
    }

    /** 黑湖新增产品以产品编号判重，已存在时由远端拒绝；仅明确的认证失效允许刷新后重试一次。 */
    public void createProduct(Map<String, Object> payload) {
        BlacklakeSession session = getSession();
        JsonNode response = post(session.restClient(), "/api/dytin/external/product/create", session.token(), payload);
        if (!SUCCESS_CODE.equals(response.path("code").asText())) {
            throw responseError("创建产品失败；业务码=" + response.path("code").asText(), response);
        }
    }

    /** 按productCode定位已有货品；远端不存在时返回失败，不回落为新增。 */
    public void updateProduct(Map<String, Object> payload) {
        BlacklakeSession session = getSession();
        JsonNode response = post(session.restClient(), "/api/dytin/external/product/update", session.token(), payload);
        if (!SUCCESS_CODE.equals(response.path("code").asText())) {
            throw responseError("修改产品失败；业务码=" + response.path("code").asText(), response);
        }
    }

    /** 读取共享 Token；Redis 不可用时明确失败，不以重复登录掩盖缓存故障。 */
    private BlacklakeSession getSession() {
        String token;
        try {
            token = stringRedisTemplate.opsForValue().get(TOKEN_KEY);
        } catch (DataAccessException exception) {
            throw remoteError("Token 读取 Redis 失败");
        }
        if (StringUtils.isNotBlank(token)) {
            return new BlacklakeSession(token, createRestClient());
        }
        return login();
    }

    private RestClient createRestClient() {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeout()))
                .cookieHandler(cookieManager).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().baseUrl(properties.getEndpoint())
                .requestFactory(requestFactory).build();
    }

    private JsonNode post(RestClient restClient, String path, String token, Object payload) {
        try {
            return postOnce(restClient, path, token, payload);
        } catch (RestClientResponseException exception) {
            if (LOGIN_PATH.equals(path) || token == null || !isTokenExpired(exception)) {
                throw httpFailure(path, exception);
            }
            log.info("黑湖认证失效，刷新 Token 后重试一次，path={}", path);
            BlacklakeSession refreshedSession = refreshSession(token);
            try {
                return postOnce(refreshedSession.restClient(), path, refreshedSession.token(), payload);
            } catch (RestClientResponseException retryException) {
                throw httpFailure(path, retryException);
            }
        }
    }

    private boolean isTokenExpired(RestClientResponseException exception) {
        if (exception.getStatusCode().value() != 401) {
            return false;
        }
        try {
            JsonNode response = objectMapper.readTree(exception.getResponseBodyAsString());
            return response != null && "401".equals(response.path("code").asText())
                    && "USER_VERIFICATION_ERROR".equals(response.path("subCode").asText());
        } catch (JsonProcessingException exceptionParsingResponse) {
            return false;
        }
    }

    /** 同一实例内串行刷新，等待期间已更新缓存时直接复用新 Token。 */
    private BlacklakeSession refreshSession(String expiredToken) {
        synchronized (tokenRefreshMonitor) {
            String cachedToken;
            try {
                cachedToken = stringRedisTemplate.opsForValue().get(TOKEN_KEY);
            } catch (DataAccessException exception) {
                throw remoteError("Token 读取 Redis 失败");
            }
            if (StringUtils.isNotBlank(cachedToken) && !expiredToken.equals(cachedToken)) {
                return new BlacklakeSession(cachedToken, createRestClient());
            }
            return login();
        }
    }

    private BusinessException httpFailure(String path, RestClientResponseException exception) {
        String responseBody = exception.getResponseBodyAsString();
        if (StringUtils.isNotBlank(responseBody)) {
            return new ExternalApiResponseException(ResultCode.SYSTEM_ERROR,
                    "黑湖HTTP请求失败，接口=" + path, responseBody);
        }
        return remoteError("HTTP请求失败且响应为空，接口=" + path);
    }

    private JsonNode postOnce(RestClient restClient, String path, String token, Object payload) {
        String body = null;
        try {
            RestClient.RequestBodySpec request = restClient.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON).header("X-CLIENT", "lite-web");
            if (token != null) {
                request.header("X-AUTH", token);
            }
            String requestBody = objectMapper.writeValueAsString(payload);
            requestLimiter.acquire(path);
            body = request.body(requestBody).retrieve().body(String.class);
            // log.info("黑湖 API response，path={}，response={}", path, ExternalApiResponseSanitizer.sanitize(path, body, objectMapper));
            if (StringUtils.isBlank(body)) {
                throw remoteError("响应为空");
            }
            JsonNode response = objectMapper.readTree(body);
            if (!response.isObject()) {
                throw responseError("响应不是 JSON 对象", response);
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // 不拼接远端响应或底层异常文本，避免暴露凭证与敏感业务数据。
            throw remoteError("HTTP 请求失败，接口=" + path);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            if (StringUtils.isNotBlank(body)) throw new ExternalApiResponseException(ResultCode.SYSTEM_ERROR,
                    "黑湖响应JSON解析失败，接口=" + path, body);
            throw remoteError("JSON 编解码失败，接口=" + path);
        }
    }

    private BusinessException responseError(String message, JsonNode response) {
        return new ExternalApiResponseException(ResultCode.SYSTEM_ERROR, "黑湖接口调用失败：" + message, response.toString());
    }

    private BusinessException parameterError(String message) {
        return new BusinessException(ResultCode.PARAM_ERROR, message);
    }

    private BusinessException remoteError(String message) {
        return new BusinessException(ResultCode.SYSTEM_ERROR, "黑湖接口调用失败：" + message);
    }
}
