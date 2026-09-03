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
public class LingxingOpenApiClient implements LingxingProductClient {

    private static final String TOKEN_PATH = "/api/auth-server/oauth/access-token";
    private static final String PRODUCT_DETAIL_PATH =
            "/erp/sc/routing/data/local_inventory/batchGetProductInfo";
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
