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
    /** 单页网络异常的最大尝试次数（含首次）。 */
    private static final int MAX_PAGE_ATTEMPTS = 3;
    private static final int MAX_REASON_LENGTH = 500;

    private final LingxingOpenApiProperties properties;
    private final LingxingSigner signer;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final LingxingRequestCoordinator coordinator;
    private volatile CachedToken cachedToken;
    /** 上一轮装箱批次详情；指纹不变时复用，避免每5分钟重复拉取391条。 */
    private volatile PackingBatchCache packingBatchCache;
    /** 预览可复用的最长缓存年龄：待关联批次未变时最多复用一小时，兜底指纹捕捉不到的字段级变化。 */
    private static final Duration PACKING_BATCH_PREVIEW_TTL = Duration.ofMinutes(60);
    /**
     * 发送前复核只接受一分钟内的缓存。
     * 预览刚重拉过（常见于缓存为空或批次变化）时，复核几秒后命中，省掉整页拉取；
     * 预览命中旧缓存时缓存本就超龄，复核照旧全量重拉，准确性不打折。
     */
    private static final Duration PACKING_BATCH_RECHECK_TTL = Duration.ofSeconds(60);

    public LingxingOpenApiClient(
            LingxingOpenApiProperties properties,
            LingxingSigner signer,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            LingxingRequestCoordinator coordinator) {
        this.properties = properties;
        this.coordinator = coordinator;
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
        try {
            JsonNode response = requestQueryWithRetry(PRODUCT_DETAIL_PATH, body);
            String code = response == null ? "" : response.path("code").asText();
            String remoteMessage = responseMessage(response);
            if (!code.isBlank() && !"0".equals(code) && !"200".equals(code)) {
                String reason = "业务码 " + code + (remoteMessage.isBlank() ? "" : "：" + remoteMessage);
                log.error("Lingxing product request was rejected: {}", sanitizeReason(reason));
                throw lingxingError(reason);
            }
            JsonNode data = response == null ? null : response.get("data");
            if (data == null || !data.isArray()) {
                String reason = remoteMessage.isBlank() ? "响应数据格式错误" : remoteMessage;
                log.error("Lingxing product response is invalid: code={}, reason={}", code, sanitizeReason(reason));
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
            try {
                JsonNode response = requestSupplierPage(body, supplierId, offset);
                // 重试方法已经确认业务成功和分页结构有效，这里只扫描当前页目标供应商。
                JsonNode data = response.get("data");
                long responseTotal = response.path("total").asLong();
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

    /** 只重试当前供应商列表页的临时失败，最多额外两次，每次间隔两秒。 */
    private JsonNode requestSupplierPage(Map<String, Object> body, long supplierId, int offset) {
        long started = System.nanoTime();
        for (int attempt = 1; attempt <= 3; attempt++) {
            String category;
            String reason;
            boolean retryable;
            Throwable safeCause = null;
            try {
                JsonNode response = requestQueued("/erp/sc/data/local_inventory/supplier", body);
                String code = response == null ? "" : response.path("code").asText();
                if ("0".equals(code) || "200".equals(code)) {
                    if (response.path("data").isArray() && response.path("total").asLong(-1) >= 0) return response;
                    category = "领星响应异常";
                    reason = "成功响应缺少有效 data 数组或 total 总数";
                    retryable = false;
                } else {
                    category = "3001008".equals(code) ? "领星限流" : "领星业务拒绝";
                    reason = "业务码=" + code + "；返回信息=" + responseMessage(response);
                    retryable = "3001008".equals(code);
                }
            } catch (RestClientException ex) {
                reason = transportFailureReason(ex);
                if (ex instanceof RestClientResponseException http) {
                    int status = http.getStatusCode().value();
                    category = status == 429 ? "领星限流" : "外部HTTP异常";
                    retryable = status == 429 || status >= 500;
                } else if (ex instanceof ResourceAccessException) {
                    category = hasCause(ex, SocketTimeoutException.class) ? "网络超时" : "网络连接异常";
                    retryable = !hasCause(ex, javax.net.ssl.SSLHandshakeException.class);
                } else {
                    category = "响应读取或解析异常";
                    retryable = false;
                }
                // 原异常可能含带 token/sign 的请求 URL：保留各层堆栈，但用安全原因替换其消息。
                safeCause = safeSupplierCause(ex);
            }
            reason = sanitizeReason(reason);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            if (!retryable || attempt == 3) {
                var failure = new com.scione.scm.bill.common.LingxingSupplierQueryException(
                        category, supplierId, attempt, elapsedMs, reason, safeCause);
                log.error("领星供应商查询最终失败：offset={}, {}", offset, failure.getMessage(), failure);
                throw failure;
            }
            log.info("领星供应商查询准备重试：supplierId={}, offset={}, category={}, attempt={}/3, elapsedMs={}, waitMs=2000, reason={}",
                    supplierId, offset, category, attempt, elapsedMs, reason);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new com.scione.scm.bill.common.LingxingSupplierQueryException(
                        "请求中断", supplierId, attempt, elapsedMs, "重试等待被中断，已停止查询", ex);
            }
        }
        throw new IllegalStateException("供应商查询重试状态异常");
    }

    private Throwable safeSupplierCause(Throwable original) {
        RuntimeException root = null;
        RuntimeException previous = null;
        Throwable current = original;
        for (int depth = 0; current != null && depth < 10; depth++) {
            RuntimeException safe = new RuntimeException(current.getClass().getName()
                    + "：" + sanitizeReason(current.getMessage()));
            safe.setStackTrace(current.getStackTrace());
            if (root == null) root = safe;
            else previous.initCause(safe);
            previous = safe;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return root;
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
            log.info("领星供应商资料里没有收款账户列表（payment_account_group 缺失或为空）");
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
                log.info("领星供应商默认收款账号字段不完整，跳过该行：户名={}, 账号={}, 开户行={}",
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
            log.info("领星供应商默认收款账户已停用（is_open=0），仍按勾选的默认账户使用：户名={}",
                    disabledDefault.accountName());
            return Optional.of(disabledDefault);
        }
        log.info("领星供应商未维护默认收款账户（无 is_default=1 的行），合同将无法带出收款账户。"
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
        Set<String> seen = new HashSet<>();
        long expectedTotal = -1;
        int offset = 0;
        while (true) {
            JsonNode response = callPurchaseOrderList(startTime, endTime, searchFieldTime, offset, null);
            JsonNode data = response == null ? null : response.get("data");
            String totalText = response == null ? "" : response.path("total").asText();
            if (data == null || !data.isArray() || !totalText.matches("[0-9]+")) {
                throw lingxingError("采购单分页缺少有效data/total，本轮进度不推进");
            }
            long total = Long.parseLong(totalText);
            if (expectedTotal >= 0 && expectedTotal != total) {
                throw lingxingError("采购单分页总数变化，本轮进度不推进，下轮重新补查");
            }
            expectedTotal = total;
            for (JsonNode order : data) {
                PurchaseOrderData item = toPurchaseOrderData(order);
                if (item.orderSn() == null || item.orderSn().isBlank() || !seen.add(item.orderSn())) {
                    throw lingxingError("采购单分页单号缺失或重复，本轮进度不推进");
                }
                all.add(item);
            }
            if (all.size() == total) break;
            if (all.size() > total || data.size() != PURCHASE_ORDER_PAGE_SIZE) {
                throw lingxingError("采购单分页提前结束或数量异常，本轮进度不推进");
            }
            offset += PURCHASE_ORDER_PAGE_SIZE;
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

        try {
            JsonNode response = requestQueryWithRetry(PURCHASE_ORDER_LIST_PATH, body);
            String code = response == null ? "" : response.path("code").asText();
            // 采购查询步骤4：检查领星业务码；HTTP 200 也可能是限流或 token 错误，不能按成功数据解析。
            String remoteMessage = responseMessage(response);
            if (!"0".equals(code) && !"200".equals(code)) {
                String reason = "业务码 " + code + (remoteMessage.isBlank() ? "" : "：" + remoteMessage);
                log.info("Lingxing purchase order request was rejected: {}", sanitizeReason(reason));
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
            JsonNode response = coordinator.execute(TOKEN_PATH,
                    () -> restClient.post().uri(uri).retrieve().body(JsonNode.class));
            JsonNode data = response == null ? null : response.get("data");
            String code = response == null ? "" : response.path("code").asText();
            String token = data == null ? null : text(data, "access_token");
            if (!"200".equals(code) || token == null || token.isBlank()) {
                String remoteMessage = responseMessage(response);
                String reason = "获取 access token 失败"
                        + (code.isBlank() ? "" : "（业务码 " + code + "）")
                        + (remoteMessage.isBlank() ? "" : "：" + remoteMessage);
                log.error("Lingxing access token response is invalid: {}", sanitizeReason(reason));
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

    public record PackingSource(List<JsonNode> tasks, List<JsonNode> batches, LocalDateTime checkedAt) { }

    /** 待关联批次指纹 + 其对应的批次详情，供每轮预览复用。 */
    private record PackingBatchCache(String fingerprint, List<JsonNode> batches, long fetchedAt) { }

    /** 发送前只读核验；不写同步表、不按本地快照代替当前领星状态。 */
    public List<JsonNode> fetchReminderVerification(String kind, Collection<String> sns, LocalDate since) {
        List<String> keys = sns.stream().distinct().toList();
        if (keys.isEmpty() && !Set.of("POOL", "CR_UPDATES").contains(kind)) return List.of();
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        List<JsonNode> result = new ArrayList<>();
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        if ("RF".equals(kind)) {
            for (String sn : keys) result.addAll(reminderPages("/basicOpen/finance/requestFunds/order/list",
                    Map.of("search_field", "order_sn", "search_value", sn), null, 200, deadline));
        } else if ("POOL".equals(kind)) {
            for (LocalDate start = since; !start.isAfter(today); start = start.plusDays(90)) {
                LocalDate end = start.plusDays(89).isAfter(today) ? today : start.plusDays(89);
                result.addAll(reminderPages("/basicOpen/finance/requestFundsPool/purchase/list",
                        Map.of("time_field", "create_time", "start_time", start.toString(), "end_time", end.toString()), null, 200, deadline));
            }
        } else if ("CR_UPDATES".equals(kind)) {
            result.addAll(reminderPages("/erp/sc/routing/deliveryReceipt/PurchaseReceiptOrder/getOrderList",
                    Map.of("date_type", 4, "start_date", since.toString(), "end_date", today.toString(), "order_type", 1), null, 500, deadline));
        } else {
            for (int offset = 0; offset < keys.size(); offset += 100) {
                List<String> batch = keys.subList(offset, Math.min(offset + 100, keys.size()));
                Map<String,Object> filter = new LinkedHashMap<>();
                filter.put("start_date", since.toString()); filter.put("end_date", today.toString());
                String path;
                switch (kind) {
                    case "PP" -> { path="/erp/sc/routing/data/local_inventory/getPurchasePlans"; filter.put("search_field_time","creator_time"); filter.put("plan_sns",batch); }
                    case "PO" -> { path=PURCHASE_ORDER_LIST_PATH; filter.put("search_field_time","create_time"); filter.put("order_sn",batch); }
                    case "CR" -> { path="/erp/sc/routing/deliveryReceipt/PurchaseReceiptOrder/getOrderList"; filter.put("date_type",3); filter.put("order_sns",String.join(",",batch)); filter.put("order_type",1); }
                    default -> throw lingxingError("未知的催办实时核验类型");
                }
                result.addAll(reminderPages(path,filter,null,"PP".equals(kind)?50:500,deadline));
            }
        }
        return result;
    }

    /**
     * 发送前复核专用：只接受一分钟内的批次详情缓存。
     * packingTask 每次都会真拉，指纹一变就重拉；一分钟的窗口只覆盖"预览刚拉完"这一种情况。
     */
    public PackingSource fetchReminderPacking(LocalDate from) {
        return fetchReminderPacking(from, PACKING_BATCH_RECHECK_TTL);
    }

    /** 每轮预览专用：指纹未变时最多复用一小时，省掉 shipmentPlanLists 整页拉取（4页约64秒）。 */
    public PackingSource fetchReminderPackingForPreview(LocalDate from) {
        return fetchReminderPacking(from, PACKING_BATCH_PREVIEW_TTL);
    }

    /**
     * @param maxCacheAge 允许复用的缓存年龄上限，超龄就重拉。
     *                    指纹不一致同样会重拉，与这个上限无关。
     */
    private PackingSource fetchReminderPacking(LocalDate from, Duration maxCacheAge) {
        if (from == null) throw lingxingError("关联货件批次日期不能为空");
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        String end = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        // packingTask/list 的 length 上限就是200，填500会直接返回 code=400；283条只能分两页。
        List<JsonNode> tasks = reminderPages("/basicOpen/packingTask/list", Map.of(
                "relateSnType",List.of("1"),"searchFieldTime","create","startDate",from.toString(),"endDate",end),
                "ptId",200,deadline);
        String fingerprint = packingFingerprint(tasks);
        PackingBatchCache cached = packingBatchCache;
        long cachedAge = cached == null ? -1 : System.nanoTime() - cached.fetchedAt();
        List<JsonNode> batches;
        if (cached != null && fingerprint.equals(cached.fingerprint())
                && cachedAge < maxCacheAge.toNanos()) {
            batches = cached.batches();
            log.info("关联货件批次详情本轮复用：待关联批次未变化，跳过shipmentPlanLists，缓存{}秒（上限{}秒）",
                    java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(cachedAge), maxCacheAge.toSeconds());
        } else {
            // 实测（2026-10-26，391条/26天）：length=100 耗时15.5秒、响应体1.42MB、成功；
            // length=200 到26.9秒被领星侧掐断（IncompleteRead/Premature EOF）；391条更早断。
            // 领星在该接口有约25秒的响应上限，超过即断连。100条是当前安全且能读完的最大页。
            batches = reminderPages("/erp/sc/data/fba_report/shipmentPlanLists",Map.of(
                    "search_field_time","gmt_create","start_date",from.toString(),"end_date",end),"ispg_id",100,deadline);
            log.info("关联货件批次详情重新拉取：{}", cached == null ? "首次查询"
                    : !fingerprint.equals(cached.fingerprint()) ? "待关联批次发生变化"
                    : "缓存超龄（已" + java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(cachedAge)
                      + "秒，允许" + maxCacheAge.toSeconds() + "秒）");
            // 无论谁允许读缓存，只要真拉了就用最新结果覆盖：
            // 复核的全量拉取顺带把缓存校准一次，不必再等 TTL 到点。
            packingBatchCache = new PackingBatchCache(fingerprint, List.copyOf(batches), System.nanoTime());
        }
        if (System.nanoTime()>=deadline) throw lingxingError("关联货件查询超过4分钟，本轮不发送");
        return new PackingSource(tasks,batches,LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 影响候选判定的字段才进指纹：批次号、任务状态、已关联货件数。
     * 子计划从"未关联"变"已关联"会改动任务状态或货件列表，指纹随之变化，缓存自然失效。
     */
    private static String packingFingerprint(List<JsonNode> tasks) {
        return tasks.stream()
                .filter(task -> task.path("relateSnType").asInt() == 1)
                .map(task -> task.path("relateSn").asText() + ":"
                        + task.path("taskStatus").asInt() + ":" + task.path("relateShipment").size())
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** 共用严格分页：装箱按明确ID去重；PP等多SKU响应按整行去重，允许同单号多行。 */
    private List<JsonNode> reminderPages(String path,Map<String,Object> filter,String key,int size,long deadline) {
        List<JsonNode> result=new ArrayList<>();Set<String> seen=new HashSet<>();Long expected=null;
        while (true) {
            if (System.nanoTime()>=deadline) throw lingxingError("实时节点查询超时");
            Map<String,Object> body=new LinkedHashMap<>(filter);body.put("offset",result.size());body.put("length",size);
            JsonNode response;
            long pageStarted = System.nanoTime();
            response=requestPage(path,body,deadline);
            if (System.nanoTime()>=deadline) throw lingxingError("实时节点查询超时");
            if (response==null || !"0".equals(response.path("code").asText()))
                throw lingxingError("实时节点接口未成功，code="+(response==null?"invalid":response.path("code").asText()));
            JsonNode data=response.path("data");JsonNode page=data.isArray()?data:data.path("list");
            // 领星各接口总数位置不一致：收货单getOrderList顶层total恒为0、真实值在data.total，
            // 其余接口反过来。两边都读、取较大值，不能因为顶层0就把有数据的页判成结构异常。
            long count=Math.max(response.path("total").asLong(-1),data.isObject()?data.path("total").asLong(-1):-1);
            if (!page.isArray() || count<0) throw lingxingError("实时节点分页结构异常");
            if (expected!=null && expected!=count) throw lingxingError("实时节点分页期间总数变化，下次重新采集");
            expected=count;
            log.info("实时催办接口页完成：path={}, offset={}, pageRows={}, total={}, elapsedMs={}", path,
                    result.size(), page.size(), count, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pageStarted));
            for (JsonNode row:page) {
                String id=key==null?row.toString():row.path(key).asText("");
                if (id.isBlank() || !seen.add(id)) throw lingxingError("实时节点业务键缺失或重复");
                result.add(row);
            }
            if (result.size()==count) return result;
            if (page.isEmpty() || result.size()>count) throw lingxingError("实时节点分页不完整");
        }
    }

    /** 同接口排队后才签名；一页/一批就是一次请求，响应读取完成后释放名额。 */
    private JsonNode requestQueued(String path, Map<String, Object> body) {
        ensureConfigured();
        // token 获取本身已有独立队列和提前一分钟刷新；不占用业务接口的开始间隔。
        String token = accessToken();
        return coordinator.execute(path, () -> {
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("timestamp", timestamp);
            parameters.put("access_token", token);
            parameters.put("app_key", properties.getAppId());
            parameters.putAll(body);
            String signature;
            try {
                signature = signer.sign(parameters, properties.getAppId());
            } catch (IllegalStateException ex) {
                throw lingxingError("请求签名失败；接口=" + path);
            }
            Map<String, String> query = new LinkedHashMap<>();
            query.put("timestamp", timestamp);
            query.put("access_token", token);
            query.put("app_key", properties.getAppId());
            query.put("sign", signature);
            URI uri = requestUri(path, query);
            return restClient.post().uri(uri).body(body).retrieve().body(JsonNode.class);
        });
    }

    /** 临时业务/网络错误最多额外重试两次；仅重试当前查询，不重跑合同创建。 */
    private JsonNode requestQueryWithRetry(String path, Map<String, Object> body) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            long waitMillis = 2000L * attempt;
            try {
                JsonNode response = requestQueued(path, body);
                String code = response == null ? "" : response.path("code").asText();
                if (!Set.of("3001008", "103", "500").contains(code) || attempt == 3) {
                    return response;
                }
                if (Set.of("3001008", "103").contains(code)) waitMillis = 30000L * attempt;
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if ((status != 408 && status != 429 && (status < 500 || status > 599)) || attempt == 3) throw ex;
                if (status == 429) waitMillis = 30000L * attempt;
            } catch (ResourceAccessException ex) {
                if (hasCause(ex, javax.net.ssl.SSLHandshakeException.class) || attempt == 3) throw ex;
            }
            log.info("领星查询临时失败，稍后重新排队：path={}, attempt={}, maxAttempts=3, waitMs={}",
                    path, attempt, waitMillis);
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw lingxingError("限流重试等待被中断；接口=" + path);
            }
        }
        throw new IllegalStateException("Unreachable retry branch");
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

    /**
     * 单页请求的网络层重试：只重试当前这一页，已拉到的页不重来。
     * 限流由 requestQueryWithRetry 内部按码处理（等30秒×次数），这里只管连接重置、读超时这类抖动。
     * 间隔2秒、4秒；每轮重试前检查4分钟 deadline，避免慢接口把整轮拖超时。
     */
    private JsonNode requestPage(String path, Map<String,Object> body, long deadline) {
        RestClientException last = null;
        for (int attempt = 1; attempt <= MAX_PAGE_ATTEMPTS; attempt++) {
            try {
                return requestQueryWithRetry(path, body);
            } catch (RestClientException exception) {
                last = exception;
                if (attempt == MAX_PAGE_ATTEMPTS) break;
                log.info("实时催办来源接口第{}次失败，{}秒后重试：path={}, exceptionType={}, msg={}, rootCause={}",
                        attempt, attempt * 2L, path, exception.getClass().getSimpleName(),
                        abbreviate(exception.getMessage()), rootCause(exception));
                try { java.util.concurrent.TimeUnit.SECONDS.sleep(attempt * 2L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw lingxingError("实时节点接口重试等待被中断，未更新数据库");
                }
                if (System.nanoTime() >= deadline) throw lingxingError("实时节点查询超时");
            }
        }
        log.error("实时催办来源接口重试{}次后仍失败：path={}, exceptionType={}, msg={}, rootCause={}",
                MAX_PAGE_ATTEMPTS - 1, path, last.getClass().getSimpleName(), abbreviate(last.getMessage()),
                rootCause(last));
        throw lingxingError("实时节点接口请求失败，未更新数据库");
    }

    /**
     * 外层异常信息常常只有"Error while extracting response"这类壳，
     * 到底是读超时、连接重置还是 JSON 截断全在 cause 链最末端，必须打出来才不用靠猜。
     */
    private static String rootCause(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) cursor = cursor.getCause();
        return cursor.getClass().getSimpleName() + ": " + abbreviate(cursor.getMessage());
    }

    /** 网络异常消息里常带完整 URL 和查询串，日志只保留前 200 字符。 */
    private static String abbreviate(String message) {
        if (message == null) return "";
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
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
        String sanitized = reason.replace('\r', ' ').replace('\n', ' ').trim()
                .replaceAll("(?i)(access_token|appSecret|app_secret|sign)=([^&\\s]+)", "$1=***")
                .replaceAll("(?<!\\d)\\d{12,}(?!\\d)", "[长数字已脱敏]");
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
