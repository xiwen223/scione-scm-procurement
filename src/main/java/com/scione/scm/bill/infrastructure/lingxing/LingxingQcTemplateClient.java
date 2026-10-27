package com.scione.scm.bill.infrastructure.lingxing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ExternalApiResponseException;
import com.scione.scm.bill.common.ExternalApiResponseSanitizer;
import org.springframework.web.client.RestClientResponseException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.LingxingCookiesCacheMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import com.scione.scm.bill.application.vo.LingxingProductVO;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class LingxingQcTemplateClient {

    private static final String ENDPOINT = "https://scione.lingxing.com";

    // 查询质检模板列表接口
    private static final String PRODUCT_LIST_PATH = "/api/product/lists";

    private static final String TEMPLATE_LIST_PATH = "/api/module/product/QcTemplate/getAllQcTemplateList";

    @Resource
    private RestTemplate restTemplate;
    @Resource
    private LingxingCookiesCacheMapper lingxingCookiesCacheMapper;
    @Resource
    private ObjectMapper objectMapper;

    public JsonNode getAllQcTemplateList() {
        URI uri = UriComponentsBuilder.fromHttpUrl(ENDPOINT)
                .path(TEMPLATE_LIST_PATH)
                .queryParam("req_time_sequence", TEMPLATE_LIST_PATH + "$$8")
                .build().encode().toUri();
        HttpHeaders headers = loadCookieHeaders(uri);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-AK-Language", "zh");
        headers.set("X-AK-PLATFORM", "1");
        headers.set("X-AK-Request-Source", "erp");
        headers.set("X-AK-Request-Id", UUID.randomUUID().toString());
        headers.set("AK-Client-Type", "web");
        headers.set("AK-Origin", ENDPOINT);
        headers.set(HttpHeaders.REFERER, ENDPOINT + "/erp/productManage");

        JsonNode response;
        try {
            response = restTemplate.exchange(uri, HttpMethod.GET,
                    new HttpEntity<Void>(headers), JsonNode.class).getBody();
        } catch (RestClientException exception) {
            if (exception instanceof RestClientResponseException responseException) {
                String body = responseException.getResponseBodyAsString();
                log.info("领星 API response，path={}，response={}", TEMPLATE_LIST_PATH, ExternalApiResponseSanitizer.sanitize(TEMPLATE_LIST_PATH, body, objectMapper));
                if (!body.isBlank()) throw new ExternalApiResponseException(ResultCode.LINGXING_API_ERROR,
                        "领星质检模板接口请求失败", body);
            }
            // 异常message不透传正文；完整响应通过独立字段交由消费端落库。
            throw new BusinessException(ResultCode.LINGXING_API_ERROR, "领星质检模板接口请求失败");
        }
        log.info("领星 API response，path={}，response={}", TEMPLATE_LIST_PATH, ExternalApiResponseSanitizer.sanitize(TEMPLATE_LIST_PATH, response == null ? null : response.toString(), objectMapper));
        if (response == null || response.isNull()) {
            throw new BusinessException(ResultCode.LINGXING_API_ERROR, "领星质检模板接口返回为空");
        }
        // 未约定远端业务码，保留完整 JSON，包含登录过期等业务失败响应。
        return response;
    }

    /** 使用当天数据库Cookie查询领星后台产品列表，产品名称搜索值及分页偏移量由调用方传入。 */
    public List<LingxingProductVO> queryProducts(String searchValue, int offset) {
        if (offset < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "offset不能小于0");
        }
        URI uri = UriComponentsBuilder.fromHttpUrl(ENDPOINT).path(PRODUCT_LIST_PATH).build().toUri();
        HttpHeaders headers = loadCookieHeaders(uri);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-AK-Language", "zh");
        headers.set("X-AK-PLATFORM", "1");
        headers.set("X-AK-Request-Source", "erp");
        headers.set("X-AK-Request-Id", UUID.randomUUID().toString());
        headers.set("AK-Client-Type", "web");
        headers.set("AK-Origin", ENDPOINT);
        headers.set(HttpHeaders.REFERER, ENDPOINT + "/erp/productManage");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("search_field_time", "create_time");
        request.put("product_creator_uid", List.of());
        request.put("product_developer_uid", List.of());
        request.put("permission_uid", List.of());
        request.put("cg_opt_uid", List.of());
        request.put("supplier_id", List.of());
        request.put("sort_field", "create_time");
        request.put("sort_type", "desc");
        request.put("search_field", "product_name");
        request.put("search_value", searchValue);
        request.put("attribute", List.of());
        request.put("status", List.of());
        request.put("open_status", "");
        request.put("gtag_ids", "");
        request.put("senior_search_list", "[]");
        request.put("single_product_id", List.of());
        request.put("is_matched_listing", "");
        request.put("is_matched_alibaba", "");
        request.put("relation_aux", "");
        request.put("is_have_pic", "");
        request.put("cg_package", "");
        request.put("cg_product_gross_weight", Map.of("left", "", "right", "", "symbol", "gt"));
        request.put("cg_price", Map.of("left", "", "right", "", "symbol", "gt"));
        request.put("cg_transport_costs", Map.of("left", "", "right", "", "symbol", "gt", "country_code", "US"));
        request.put("offset", offset);
        request.put("is_combo", "0");
        request.put("length", 500);
        request.put("is_aux", 0);
        request.put("product_type", List.of(1, 2));
        request.put("selected_product_ids", "");
        request.put("req_time_sequence", PRODUCT_LIST_PATH + "$$22");

        JsonNode response;
        try {
            response = restTemplate.exchange(uri, HttpMethod.POST,
                    new HttpEntity<>(request, headers), JsonNode.class).getBody();
        } catch (RestClientException exception) {
            if (exception instanceof RestClientResponseException responseException) {
                String body = responseException.getResponseBodyAsString();
                log.info("领星 API response，path={}，response={}", PRODUCT_LIST_PATH,
                        ExternalApiResponseSanitizer.sanitize(PRODUCT_LIST_PATH, body, objectMapper));
                if (!body.isBlank()) throw new ExternalApiResponseException(ResultCode.LINGXING_API_ERROR,
                        "领星后台产品列表接口请求失败", body);
            }
            throw new BusinessException(ResultCode.LINGXING_API_ERROR, "领星后台产品列表接口请求失败");
        }
        log.info("领星 API response，path={}，response={}", PRODUCT_LIST_PATH,
                ExternalApiResponseSanitizer.sanitize(PRODUCT_LIST_PATH, response == null ? null : response.toString(), objectMapper));
        if (response == null || response.isNull()) {
            throw new BusinessException(ResultCode.LINGXING_API_ERROR, "领星后台产品列表接口返回为空");
        }
        if (!"1".equals(response.path("code").asText())) {
            throw new ExternalApiResponseException(ResultCode.LINGXING_API_ERROR,
                    "领星后台产品列表查询失败", response.toString());
        }
        JsonNode products = response.get("list");
        if (products == null || !products.isArray()) {
            throw new ExternalApiResponseException(ResultCode.LINGXING_API_ERROR,
                    "领星后台产品列表响应缺少list数组", response.toString());
        }
        List<LingxingProductVO> result = new ArrayList<>();
        for (JsonNode product : products) {
            LingxingProductVO summary = new LingxingProductVO();
            summary.setSku(product.path("sku").asText(null));
            summary.setProductName(product.path("product_name").asText(null));
            summary.setCgPrice(product.path("cg_price").asText(null));
            result.add(summary);
        }
        return result;
    }

    private HttpHeaders loadCookieHeaders(URI uri) {
        LocalDate cookieDate = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String cookiesJson = lingxingCookiesCacheMapper.findCookiesByDate(cookieDate);
        if (!StringUtils.hasText(cookiesJson)) {
            throw new BusinessException(ResultCode.LINGXING_API_ERROR, "未找到当天领星 Cookie：" + cookieDate);
        }
        JsonNode cookies;
        try {
            cookies = objectMapper.readTree(cookiesJson);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.LINGXING_API_ERROR, "当天领星 Cookie JSON 格式错误");
        }
        if (cookies == null || !cookies.isArray()) {
            throw new BusinessException(ResultCode.LINGXING_API_ERROR, "当天领星 Cookie 必须为 JSON 数组");
        }

        Map<String, String> cookieValues = new HashMap<>();
        StringJoiner cookieHeader = new StringJoiner("; ");
        for (JsonNode cookie : cookies) {
            String domain = cookie.path("domain").asText();
            String cookiePath = cookie.path("path").asText("/");
            // 缓存包含百度、广告等第三方 Cookie，只发送目标域名和路径匹配的 Cookie。
            boolean domainMatches = domain.startsWith(".")
                    ? uri.getHost().equals(domain.substring(1)) || uri.getHost().endsWith(domain)
                    : uri.getHost().equals(domain);
            boolean pathMatches = uri.getPath().equals(cookiePath)
                    || uri.getPath().startsWith(cookiePath.endsWith("/") ? cookiePath : cookiePath + "/");
            if (!domainMatches || !pathMatches
                    || (cookie.path("secure").asBoolean() && !"https".equals(uri.getScheme()))) {
                continue;
            }
            String name = cookie.path("name").asText();
            JsonNode valueNode = cookie.get("value");
            if (!StringUtils.hasText(name) || valueNode == null || !valueNode.isTextual()) {
                throw new BusinessException(ResultCode.LINGXING_API_ERROR, "当天领星 Cookie 字段格式错误");
            }
            String value = valueNode.asText();
            if (name.contains(";") || name.contains("=") || name.contains("\r") || name.contains("\n")
                    || value.contains(";") || value.contains("\r") || value.contains("\n")) {
                throw new BusinessException(ResultCode.LINGXING_API_ERROR, "当天领星 Cookie 包含非法字符");
            }
            cookieValues.put(name, value);
            cookieHeader.add(name + "=" + value);
        }

        String authToken = cookieValues.get("auth-token");
        if (!StringUtils.hasText(authToken)) {
            authToken = cookieValues.get("authToken");
        }
        String envKey = cookieValues.get("env_key");
        if (!StringUtils.hasText(envKey)) {
            envKey = cookieValues.get("envKey");
        }
        if (!StringUtils.hasText(authToken) || !StringUtils.hasText(envKey)
                || !StringUtils.hasText(cookieValues.get("company_id"))
                || !StringUtils.hasText(cookieValues.get("uid"))
                || !StringUtils.hasText(cookieValues.get("zid"))) {
            throw new BusinessException(ResultCode.LINGXING_API_ERROR, "当天领星 Cookie 缺少必要鉴权字段");
        }
        try {
            // Cookie 请求头保留原编码；鉴权请求头解码百分号，保留原始加号。
            authToken = URLDecoder.decode(authToken.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.LINGXING_API_ERROR, "领星 Cookie Token 编码错误");
        }
        if (authToken.contains("\r") || authToken.contains("\n")) {
            throw new BusinessException(ResultCode.LINGXING_API_ERROR, "领星 Cookie Token 包含非法字符");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookieHeader.toString());
        headers.set("auth-token", authToken);
        headers.set("X-AK-Company-Id", cookieValues.get("company_id"));
        headers.set("X-AK-ENV-KEY", envKey);
        headers.set("X-AK-Uid", cookieValues.get("uid"));
        headers.set("X-AK-Zid", cookieValues.get("zid"));
        return headers;
    }
}
