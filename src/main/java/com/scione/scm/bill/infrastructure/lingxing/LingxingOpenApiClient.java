package com.scione.scm.bill.infrastructure.lingxing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.application.port.LingxingProductClient;
import com.scione.scm.bill.application.port.LingxingPurchaseOrderClient;
import com.scione.scm.bill.application.port.LingxingSupplierClient;
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
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 直接调用领星 OpenAPI 查询商品，不读取或回退本地 lx_product 表。
 */
@Slf4j
@Component
public class LingxingOpenApiClient implements LingxingProductClient, LingxingPurchaseOrderClient, LingxingSupplierClient {

    private static final String TOKEN_PATH = "/api/auth-server/oauth/access-token";
    private static final String PRODUCT_DETAIL_PATH =
            "/erp/sc/routing/data/local_inventory/batchGetProductInfo";
    private static final String PURCHASE_ORDER_LIST_PATH =
            "/erp/sc/routing/data/local_inventory/purchaseOrderList";
    private static final int PURCHASE_ORDER_PAGE_SIZE = 500;
    /**
     * batchGetProductInfo 单次请求最多携带的 SKU 数；合同明细超过这个量时自动分批。
     *
     * <p>领星官方契约：{@code skus} / {@code productIds} / {@code sku_identifiers} 三选一，
     * 上限均为 100（令牌桶容量 1）。取满上限可以减少请求次数、降低触发频控的概率。</p>
     */
    private static final int PRODUCT_BATCH_SIZE = 100;
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
        if (sku == null || sku.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(findBySkus(List.of(sku)).get(sku));
    }

    @Override
    public Map<String, ProductDetail> findBySkus(Collection<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return Map.of();
        }
        List<String> requested = skus.stream()
                .filter(sku -> sku != null && !sku.isBlank())
                .distinct()
                .toList();
        if (requested.isEmpty()) {
            return Map.of();
        }
        Map<String, ProductDetail> products = new LinkedHashMap<>();
        for (int start = 0; start < requested.size(); start += PRODUCT_BATCH_SIZE) {
            List<String> batch = requested.subList(start, Math.min(start + PRODUCT_BATCH_SIZE, requested.size()));
            for (ProductDetail detail : requestProductBatch(batch)) {
                products.put(detail.sku(), detail);
            }
        }
        return products;
    }

    /** 请求一批 SKU；批内查不到的 SKU 不会出现在返回值里。 */
    private List<ProductDetail> requestProductBatch(List<String> skus) {
        ensureConfigured();
        Map<String, Object> body = Map.of("skus", skus);
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
            // 只保留本批请求过的 SKU，避免接口返回多余商品污染结果
            Set<String> requestedSkus = new HashSet<>(skus);
            List<ProductDetail> details = new ArrayList<>();
            for (JsonNode item : data) {
                String itemSku = text(item, "sku");
                if (itemSku != null && requestedSkus.contains(itemSku)) {
                    details.add(toProductDetail(item));
                }
            }
            return details;
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

    /**
     * 从供应商资料的 payment_account_group 中选择默认收款账号。
     * 收款账号可能不存在，不影响合同生成；模板会保留原结算说明。
     */
    @Override
    public Optional<SupplierPaymentAccount> findDefaultPaymentAccount(long supplierId) {
        return findSupplierProfile(supplierId).flatMap(SupplierProfile::defaultPaymentAccount);
    }

    /** 银行账号的日志脱敏：只留首尾各两位。户名/账户名称是业务数据，按既有习惯明文记录，便于核对白名单。 */
    static String maskAccountId(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return "***";
        }
        return trimmed.substring(0, 2) + "***" + trimmed.substring(trimmed.length() - 2);
    }

    @Override
    public Optional<SupplierProfile> findSupplierProfile(long supplierId) {
        return findSupplierById(supplierId).map(supplier -> new SupplierProfile(
                text(supplier, "address_full"),
                text(supplier, "credit_code"),
                text(supplier, "prepay_percent"),
                text(supplier, "settlement_method_text"),
                defaultPaymentAccount(supplier)));
    }

    /**
     * 取供应商的收款账号，规则只认领星「供应商 - 收款账户」里勾选的那一行。
     *
     * <p>取数口径：遍历 {@code payment_account_group}，**只保留 {@code is_default=1} 的行**，
     * 不取数组第一行（第一行通常是「中国银行」这类开户行名，未必是默认账号）。
     * 配了多个默认账户时优先取启用的；只剩停用的默认账户也会用，但会告警——付款对象不能悄悄换人。
     * 一个默认账户都没有时返回空，由调用方按「领星未维护默认收款账户」拦截，
     * **绝不退回第一行兜底**：收款账户错人比缺字段严重得多。</p>
     */
    private Optional<SupplierPaymentAccount> defaultPaymentAccount(JsonNode supplier) {
        JsonNode accounts = supplier.path("payment_account_group");
        if (!accounts.isArray() || accounts.isEmpty()) {
            log.warn("领星供应商资料里没有收款账户列表（payment_account_group 缺失或为空）");
            return Optional.empty();
        }
        SupplierPaymentAccount disabledDefault = null;
        List<String> candidates = new ArrayList<>();
        for (JsonNode account : accounts) {
            boolean isDefault = isDefaultPaymentAccount(account);
            // 逐个账户留痕：出问题时能从日志直接看出「哪一行被标成了默认」，不必再去翻接口原文。
            candidates.add("账户名称=" + dash(text(account, "name"))
                    + ", 户名=" + dash(text(account, "account_name"))
                    + ", 默认=" + dash(text(account, "is_default"))
                    + ", 启用=" + dash(text(account, "is_open")));
            if (!isDefault) {
                continue;
            }
            String accountName = text(account, "account_name");
            String accountId = text(account, "account_id");
            String bankName = text(account, "bank_name");
            if (isBlank(accountName) || isBlank(accountId) || isBlank(bankName)) {
                // 原来是直接 return 空，会把后面「字段完整的默认账户」一起丢掉，这里改成继续往后找。
                log.warn("领星供应商默认收款账号字段不完整，跳过该行：户名={}, 账号={}, 开户行={}",
                        accountName, maskAccountId(accountId), bankName);
                continue;
            }
            SupplierPaymentAccount candidate = new SupplierPaymentAccount(accountName, accountId, bankName);
            if (isEnabledPaymentAccount(account)) {
                log.info("使用领星供应商默认收款账户：户名={}, 开户行={}, 账号={}",
                        accountName, bankName, maskAccountId(accountId));
                return Optional.of(candidate);
            }
            if (disabledDefault == null) {
                disabledDefault = candidate;
            }
        }
        if (disabledDefault != null) {
            log.warn("领星供应商默认收款账户已停用（is_open=0），仍按勾选的默认账户使用：户名={}",
                    disabledDefault.accountName());
            return Optional.of(disabledDefault);
        }
        log.warn("领星供应商未维护默认收款账户（无 is_default=1 的行），合同将无法带出收款账户。"
                + "收款账户共 {} 条：{}", accounts.size(), String.join(" | ", candidates));
        return Optional.empty();
    }

    /** 日志占位：字段缺失时打 "-"，避免日志里出现 "null"。 */
    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static boolean isDefaultPaymentAccount(JsonNode account) {
        JsonNode defaultFlag = account.get("is_default");
        return defaultFlag != null && !defaultFlag.isNull()
                && (defaultFlag.asInt() == 1 || defaultFlag.asBoolean(false));
    }

    /** is_open：1 启用、0 停用；字段缺失时不擅自判定为停用。 */
    private static boolean isEnabledPaymentAccount(JsonNode account) {
        JsonNode openFlag = account.get("is_open");
        if (openFlag == null || openFlag.isNull()) {
            return true;
        }
        if (openFlag.isBoolean()) {
            return openFlag.asBoolean();
        }
        return openFlag.asInt(1) != 0;
    }

    @Override
    public List<PurchaseOrderData> fetchPurchaseOrders(
            LocalDateTime startTime, LocalDateTime endTime, String searchFieldTime) {
        ensureConfigured();
        List<PurchaseOrderData> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            JsonNode response = callPurchaseOrderList(startTime, endTime, searchFieldTime, offset, null);
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

    @Override
    public Optional<PurchaseOrderData> findByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return Optional.empty();
        }
        return findByOrderNos(List.of(orderNo)).stream().findFirst();
    }

    @Override
    public List<PurchaseOrderData> findByOrderNos(List<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return List.of();
        }
        List<String> normalized = orderNos.stream()
                .filter(no -> no != null && !no.isBlank()).map(String::trim).distinct().toList();
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.size() > PURCHASE_ORDER_PAGE_SIZE) {
            throw new IllegalArgumentException("采购单批量查询一次最多 500 个单号");
        }
        ensureConfigured();
        // order_sn 为精确筛选条件；时间范围仅满足领星列表接口的必传约束。
        JsonNode response = callPurchaseOrderList(
                LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.now(), "create_time", 0,
                normalized);
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }
        Map<String, PurchaseOrderData> matched = new LinkedHashMap<>();
        Set<String> requested = new HashSet<>(normalized);
        for (JsonNode order : data) {
            PurchaseOrderData result = toPurchaseOrderData(order);
            if (requested.contains(result.orderSn())) {
                matched.putIfAbsent(result.orderSn(), result);
            }
        }
        return new ArrayList<>(matched.values());
    }

    /** 发一页请求：签名/query 拼接与 findBySku 一致，只是换了 path 和 body。 */
    private JsonNode callPurchaseOrderList(
            LocalDateTime startTime, LocalDateTime endTime, String searchFieldTime, int offset,
            List<String> orderSns) {
        // 采购查询步骤1：构造时间窗口、分页及可选 PO 单号条件；单号查询也须满足日期必传要求。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("start_date", startTime.format(LINGXING_DATE_TIME));
        body.put("end_date", endTime.format(LINGXING_DATE_TIME));
        if (searchFieldTime != null && !searchFieldTime.isBlank()) {
            body.put("search_field_time", searchFieldTime);
        }
        body.put("offset", offset);
        body.put("length", PURCHASE_ORDER_PAGE_SIZE);
        if (orderSns != null && !orderSns.isEmpty()) {
            body.put("order_sn", orderSns);
        }

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String accessToken = accessToken();

        // 采购查询步骤2：把正文和公共参数一起参与签名，签名后的请求内容不能再变更。
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
        // 采购查询步骤3：公共鉴权放 query，采购筛选参数放 body，拼接 URI 时由统一方法编码。
        URI uri = requestUri(PURCHASE_ORDER_LIST_PATH, queryParameters);
        try {
            JsonNode response = restClient.post().uri(uri).body(body).retrieve().body(JsonNode.class);
            String code = response == null ? "" : response.path("code").asText();
            // 采购查询步骤4：检查领星业务码；HTTP 200 也可能是限流或 token 错误，不能按成功数据解析。
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
                intValue(o, "is_tax"),
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
                specification(item),
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
                attributeJson(item),
                text(item, "pic_url"));
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

    /**
     * 采购单接口的 model 经常为空，实际商品规格通常放在 attribute 数组中。
     * 规格以“型号；属性名：属性值”落库，确保后续创建合同无需再次请求领星。
     */
    private static String specification(JsonNode item) {
        List<String> parts = new ArrayList<>();
        String model = text(item, "model");
        if (!isBlank(model)) parts.add(model.trim());
        JsonNode attributes = item.get("attribute");
        if (attributes != null && attributes.isArray()) {
            for (JsonNode attribute : attributes) {
                String name = text(attribute, "attr_name");
                String value = text(attribute, "attr_value");
                if (isBlank(value)) continue;
                parts.add(isBlank(name) ? value.trim() : name.trim() + "：" + value.trim());
            }
        }
        String result = parts.stream().distinct().collect(java.util.stream.Collectors.joining("；"));
        return result.length() <= 255 ? result : result.substring(0, 255);
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
