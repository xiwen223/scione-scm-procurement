package com.scione.scm.bill.infrastructure.lingxing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.application.port.LingxingProductClient;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.config.LingxingOpenApiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import com.scione.scm.bill.application.port.LingxingPurchaseOrderClient;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * 直接调用领星 OpenAPI 查询商品，不读取或回退本地 lx_product 表。
 */
@Slf4j
@Component
public class LingxingOpenApiClient implements LingxingProductClient ,LingxingPurchaseOrderClient  {

    private static final String TOKEN_PATH = "/api/auth-server/oauth/access-token";
    private static final String PRODUCT_DETAIL_PATH =
            "/erp/sc/routing/data/local_inventory/batchGetProductInfo";
    private static final String PURCHASE_ORDER_LIST_PATH =
            "/erp/sc/routing/data/local_inventory/purchaseOrderList";
    private static final int PURCHASE_ORDER_PAGE_SIZE = 500;
    private static final DateTimeFormatter LINGXING_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter LINGXING_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Duration TOKEN_REFRESH_AHEAD = Duration.ofMinutes(1);
    private static final int MAX_REASON_LENGTH = 500;

    private final LingxingOpenApiProperties properties;
    private final LingxingSigner signer;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private volatile CachedToken cachedToken;

    public LingxingOpenApiClient(
            LingxingOpenApiProperties properties,
            LingxingSigner signer,
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

    @Override
    public Optional<ProductDetail> findBySku(String sku) {
        ensureConfigured();
        Map<String, Object> body = Map.of("skus", List.of(sku));
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String accessToken = accessToken();

        Map<String, Object> signatureParameters = new HashMap<>();
        signatureParameters.put("timestamp", timestamp);
        signatureParameters.put("access_token", accessToken);
        signatureParameters.put("app_key", properties.getAppId());
        signatureParameters.putAll(body);

        final String signature;
        try {
            signature = signer.sign(signatureParameters, properties.getAppId());
        } catch (IllegalStateException exception) {
            log.error("Failed to sign Lingxing product request: {}", exception.getMessage());
            throw lingxingError("请求签名失败");
        }

        Map<String, String> queryParameters = new LinkedHashMap<>();
        queryParameters.put("timestamp", timestamp);
        queryParameters.put("access_token", accessToken);
        queryParameters.put("app_key", properties.getAppId());
        queryParameters.put("sign", signature);
        URI uri = requestUri(PRODUCT_DETAIL_PATH, queryParameters);
        try {
            JsonNode response = restClient.post()
                    .uri(uri)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            String code = response == null ? "" : response.path("code").asText();
            String remoteMessage = responseMessage(response);
            if (!code.isBlank() && !"0".equals(code) && !"200".equals(code)) {
                String reason = "业务码 " + code + (remoteMessage.isBlank() ? "" : "：" + remoteMessage);
                log.warn("Lingxing product request was rejected: {}", sanitizeReason(reason));
                throw lingxingError(reason);
            }
            JsonNode data = response == null ? null : response.get("data");
            if (data == null || !data.isArray()) {
                String reason = remoteMessage.isBlank() ? "响应数据格式错误" : remoteMessage;
                log.warn("Lingxing product response is invalid: code={}, reason={}", code, sanitizeReason(reason));
                throw lingxingError(reason);
            }
            for (JsonNode item : data) {
                if (sku.equals(text(item, "sku"))) {
                    return Optional.of(toProductDetail(item));
                }
            }
            return Optional.empty();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            String reason = transportFailureReason(exception);
            log.error("Lingxing product request failed: {}", sanitizeReason(reason));
            throw lingxingError(reason);
        }
    }

    /**
     * 查询领星供应商列表，并按系统供应商 ID 返回匹配的原始 JSON 对象。
     */
    public Optional<JsonNode> findSupplierById(long supplierId) {
        if (supplierId <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "供应商 ID 必须为正整数");
        }
        ensureConfigured();

        final int pageSize = 1_000;
        int offset = 0;
        long total;
        do {
            Map<String, Object> body = Map.of("offset", offset, "length", pageSize);
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String accessToken = accessToken();

            Map<String, Object> signatureParameters = new HashMap<>();
            signatureParameters.put("timestamp", timestamp);
            signatureParameters.put("access_token", accessToken);
            signatureParameters.put("app_key", properties.getAppId());
            signatureParameters.putAll(body);

            final String signature;
            try {
                signature = signer.sign(signatureParameters, properties.getAppId());
            } catch (IllegalStateException exception) {
                log.error("Failed to sign Lingxing supplier request: {}", exception.getMessage());
                throw lingxingError("请求签名失败");
            }

            Map<String, String> queryParameters = new LinkedHashMap<>();
            queryParameters.put("timestamp", timestamp);
            queryParameters.put("access_token", accessToken);
            queryParameters.put("app_key", properties.getAppId());
            queryParameters.put("sign", signature);
            URI uri = requestUri("/erp/sc/data/local_inventory/supplier", queryParameters);
            try {
                JsonNode response = restClient.post()
                        .uri(uri)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                String code = response == null ? "" : response.path("code").asText();
                String remoteMessage = responseMessage(response);
                if (!"0".equals(code) && !"200".equals(code)) {
                    String reason = "业务码 " + (code.isBlank() ? "为空" : code)
                            + (remoteMessage.isBlank() ? "" : "：" + remoteMessage);
                    log.warn("Lingxing supplier request was rejected: {}", sanitizeReason(reason));
                    throw lingxingError(reason);
                }
                JsonNode data = response == null ? null : response.get("data");
                long responseTotal = response == null ? -1 : response.path("total").asLong(-1);
                if (data == null || !data.isArray() || responseTotal < 0) {
                    String reason = remoteMessage.isBlank() ? "响应数据格式错误" : remoteMessage;
                    log.warn("Lingxing supplier response is invalid: code={}, reason={}",
                            code, sanitizeReason(reason));
                    throw lingxingError(reason);
                }
                for (JsonNode item : data) {
                    Long remoteSupplierId = longValue(item, "supplier_id");
                    if (remoteSupplierId != null && remoteSupplierId == supplierId) {
                        return Optional.of(item);
                    }
                }
                if (data.isEmpty()) {
                    return Optional.empty();
                }
                offset += data.size();
                total = responseTotal;
            } catch (BusinessException exception) {
                throw exception;
            } catch (RestClientException exception) {
                String reason = transportFailureReason(exception);
                log.error("Lingxing supplier request failed: {}", sanitizeReason(reason));
                throw lingxingError(reason);
            }
        } while (offset < total);
        return Optional.empty();
    }

    @Override
    public List<PurchaseOrderData> fetchPurchaseOrders(
            LocalDateTime startTime, LocalDateTime endTime, String searchFieldTime) {
        ensureConfigured();
        List<PurchaseOrderData> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            JsonNode response = callPurchaseOrderList(startTime, endTime, searchFieldTime, offset);
            JsonNode data = response == null ? null : response.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) {
                break;                       // 查不到就结束，不抛异常
            }
            for (JsonNode order : data) {
                all.add(toPurchaseOrderData(order));
            }
            long total = response.path("total").asLong(all.size());
            offset += PURCHASE_ORDER_PAGE_SIZE;
            if (offset >= total) {
                break;                       // 翻完最后一页
            }
        }
        return all;
    }

    /** 发一页请求：签名/query 拼接与 findBySku 一致，只是换了 path 和 body。 */
    private JsonNode callPurchaseOrderList(
            LocalDateTime startTime, LocalDateTime endTime, String searchFieldTime, int offset) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("start_date", startTime.format(LINGXING_DATE_TIME));
        body.put("end_date", endTime.format(LINGXING_DATE_TIME));
        if (searchFieldTime != null && !searchFieldTime.isBlank()) {
            body.put("search_field_time", searchFieldTime);
        }
        body.put("offset", offset);
        body.put("length", PURCHASE_ORDER_PAGE_SIZE);

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String accessToken = accessToken();

        Map<String, Object> signatureParameters = new HashMap<>();
        signatureParameters.put("timestamp", timestamp);
        signatureParameters.put("access_token", accessToken);
        signatureParameters.put("app_key", properties.getAppId());
        signatureParameters.putAll(body);

        final String signature;
        try {
            signature = signer.sign(signatureParameters, properties.getAppId());
        } catch (IllegalStateException exception) {
            log.error("Failed to sign Lingxing purchase order request: {}", exception.getMessage());
            throw lingxingError("请求签名失败");
        }

        Map<String, String> queryParameters = new LinkedHashMap<>();
        queryParameters.put("timestamp", timestamp);
        queryParameters.put("access_token", accessToken);
        queryParameters.put("app_key", properties.getAppId());
        queryParameters.put("sign", signature);
        URI uri = requestUri(PURCHASE_ORDER_LIST_PATH, queryParameters);
        try {
            JsonNode response = restClient.post().uri(uri).body(body).retrieve().body(JsonNode.class);
            String code = response == null ? "" : response.path("code").asText();
            String remoteMessage = responseMessage(response);
            if (!code.isBlank() && !"0".equals(code) && !"200".equals(code)) {
                String reason = "业务码 " + code + (remoteMessage.isBlank() ? "" : "：" + remoteMessage);
                log.warn("Lingxing purchase order request was rejected: {}", sanitizeReason(reason));
                throw lingxingError(reason);
            }
            return response;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            String reason = transportFailureReason(exception);
            log.error("Lingxing purchase order request failed: {}", sanitizeReason(reason));
            throw lingxingError(reason);
        }
    }

    /** 单头映射：领星字段 → PurchaseOrderData（全部可空，不校验）。 */
    private PurchaseOrderData toPurchaseOrderData(JsonNode o) {
        List<PurchaseOrderItemData> items = new ArrayList<>();
        JsonNode itemList = o.get("item_list");
        if (itemList != null && itemList.isArray()) {
            for (JsonNode item : itemList) {
                items.add(toPurchaseOrderItem(item));
            }
        }
        return new PurchaseOrderData(
                text(o, "order_sn"),
                text(o, "custom_order_sn"),
                longValue(o, "supplier_id"),
                text(o, "supplier_name"),
                text(o, "contact_person"),
                text(o, "contact_number"),
                intValue(o, "status"),
                text(o, "status_text"),
                intValue(o, "status_shipped"),
                text(o, "status_shipped_text"),
                decimalText(o, "amount_total"),
                decimalText(o, "total_price"),
                intValue(o, "quantity_total"),
                text(o, "ware_house_name"),
                text(o, "remark"),
                dateTime(o, "order_time"),
                dateTime(o, "create_time"),
                dateTime(o, "update_time"),
                List.copyOf(items));
    }

    /** 明细映射。 */
    private PurchaseOrderItemData toPurchaseOrderItem(JsonNode item) {
        return new PurchaseOrderItemData(
                longValue(item, "id"),
                text(item, "plan_sn"),
                longValue(item, "product_id"),
                text(item, "product_name"),
                text(item, "sku"),
                text(item, "fnsku"),
                text(item, "model"),
                decimalText(item, "price"),
                decimalText(item, "amount"),
                intValue(item, "quantity_plan"),
                intValue(item, "quantity_real"),
                intValue(item, "quantity_receive"),
                text(item, "tax_rate"),
                text(item, "spu"),
                text(item, "spu_name"),
                text(item, "ware_house_name"),
                date(item, "expect_arrive_time"),
                text(item, "remark"),
                attributeJson(item));
    }

    /** attribute 数组原文转 JSON 字符串存库；失败降级 null。 */
    private String attributeJson(JsonNode item) {
        JsonNode attr = item.get("attribute");
        if (attr == null || attr.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attr);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /** 领星整型（int 字段用，避免 longValue 返回 Long）。 */
    private static Integer intValue(JsonNode node, String field) {
        Long value = longValue(node, field);
        return value == null ? null : value.intValue();
    }

    /** "yyyy-MM-dd HH:mm:ss" → LocalDateTime，空/非法一律 null（拉取阶段不抛异常）。 */
    private static LocalDateTime dateTime(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.trim(), LINGXING_DATE_TIME);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /** "yyyy-MM-dd" → LocalDate，空/非法一律 null。 */
    private static LocalDate date(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim(), LINGXING_DATE);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
    /** 金额字段：领星常返回字符串 "660.00"，decimalValue() 对字符串会返回 0，这里兼容数字和字符串。 */
    private static BigDecimal decimalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        String s = value.asText();
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String accessToken() {
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

    private CachedToken requestAccessToken(Instant requestedAt) {
        Map<String, String> queryParameters = new LinkedHashMap<>();
        queryParameters.put("appId", properties.getAppId());
        queryParameters.put("appSecret", properties.getAppSecret());
        URI uri = requestUri(TOKEN_PATH, queryParameters);
        try {
            JsonNode response = restClient.post().uri(uri).retrieve().body(JsonNode.class);
            JsonNode data = response == null ? null : response.get("data");
            String code = response == null ? "" : response.path("code").asText();
            String token = data == null ? null : text(data, "access_token");
            if (!"200".equals(code) || token == null || token.isBlank()) {
                String remoteMessage = responseMessage(response);
                String reason = "获取 access token 失败"
                        + (code.isBlank() ? "" : "（业务码 " + code + "）")
                        + (remoteMessage.isBlank() ? "" : "：" + remoteMessage);
                log.warn("Lingxing access token response is invalid: {}", sanitizeReason(reason));
                throw lingxingError(reason);
            }
            long expiresInSeconds = positiveLong(data, "expires_in",
                    positiveLong(data, "expiresIn", properties.getTokenTtl().toSeconds()));
            Duration ttl = Duration.ofSeconds(expiresInSeconds);
            Instant expiresAt = requestedAt.plus(ttl.compareTo(TOKEN_REFRESH_AHEAD) > 0
                    ? ttl.minus(TOKEN_REFRESH_AHEAD) : ttl.dividedBy(2));
            return new CachedToken(token, expiresAt);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            String reason = "获取 access token 失败：" + transportFailureReason(exception);
            log.error("Lingxing access token request failed: {}", sanitizeReason(reason));
            throw lingxingError(reason);
        }
    }

    private ProductDetail toProductDetail(JsonNode item) {
        List<ComboProduct> comboProducts = new ArrayList<>();
        JsonNode combos = item.get("combo_product_list");
        if (combos != null && combos.isArray()) {
            for (JsonNode combo : combos) {
                comboProducts.add(new ComboProduct(
                        longValue(combo, "product_id"), longValue(combo, "quantity"), text(combo, "sku")));
            }
        }
        return new ProductDetail(
                longValue(item, "id"), longValue(item, "cid"), longValue(item, "bid"), text(item, "sku"),
                text(item, "sku_identifier"), text(item, "product_name"), text(item, "pic_url"),
                longValue(item, "cg_delivery"), decimalValue(item, "cg_transport_costs"),
                text(item, "purchase_remark"), decimalValue(item, "cg_price"), longValue(item, "status"),
                longValue(item, "open_status"), longValue(item, "is_combo"), longValue(item, "create_time"),
                longValue(item, "update_time"), longValue(item, "product_developer_uid"),
                longValue(item, "cg_opt_uid"), text(item, "cg_opt_username"), text(item, "spu"),
                longValue(item, "ps_id"), nullableNode(item, "attribute"), text(item, "brand_name"),
                text(item, "category_name"), text(item, "status_text"), text(item, "product_developer"),
                nullableNode(item, "supplier_quote"), nullableNode(item, "aux_relation_list"),
                nullableNode(item, "custom_fields"), nullableNode(item, "global_tags"), List.copyOf(comboProducts));
    }

    private URI requestUri(String path, Map<String, String> queryParameters) {
        String baseUri = UriComponentsBuilder.fromUri(properties.getEndpoint())
                .path(path)
                .build()
                .toUriString();
        StringJoiner query = new StringJoiner("&");
        queryParameters.forEach((key, value) -> query.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return URI.create(baseUri + "?" + query);
    }

    private String transportFailureReason(RestClientException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            String remoteMessage = responseMessage(responseException.getResponseBodyAsString());
            String httpReason = "HTTP " + responseException.getStatusCode().value();
            return remoteMessage.isBlank() ? httpReason : httpReason + "：" + remoteMessage;
        }
        if (exception instanceof ResourceAccessException
                && hasCause(exception, SocketTimeoutException.class)) {
            return "请求超时";
        }
        if (exception instanceof ResourceAccessException) {
            return "网络连接失败";
        }
        return "请求失败（" + exception.getClass().getSimpleName() + "）";
    }

    private String responseMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
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
        for (String field : List.of("message", "msg", "error_description", "error")) {
            String message = response.path(field).asText();
            if (!message.isBlank()) {
                return message;
            }
        }
        return "";
    }

    private BusinessException lingxingError(String reason) {
        String safeReason = sanitizeReason(reason);
        String message = ResultCode.LINGXING_API_ERROR.getMessage()
                + (safeReason.isBlank() ? "" : "：" + safeReason);
        return new BusinessException(ResultCode.LINGXING_API_ERROR, message);
    }

    private String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
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
        return sanitized.length() <= MAX_REASON_LENGTH
                ? sanitized
                : sanitized.substring(0, MAX_REASON_LENGTH) + "...";
    }

    private void ensureConfigured() {
        if (properties.getEndpoint() == null || isBlank(properties.getAppId()) || isBlank(properties.getAppSecret())) {
            log.error("Lingxing OpenAPI credentials are not configured");
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "领星 OpenAPI 配置不完整");
        }
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.canConvertToLong() ? null : value.longValue();
    }

    private static long positiveLong(JsonNode node, String field, long fallback) {
        Long value = longValue(node, field);
        return value != null && value > 0 ? value : fallback;
    }

    private static BigDecimal decimalValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return value.decimalValue();
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static JsonNode nullableNode(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
    }

    private record CachedToken(String value, Instant expiresAt) {

        private boolean isValidAt(Instant instant) {
            return instant.isBefore(expiresAt);
        }
    }
}
