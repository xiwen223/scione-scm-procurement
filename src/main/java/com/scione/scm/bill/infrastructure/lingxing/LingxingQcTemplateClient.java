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
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class LingxingQcTemplateClient {

    private static final String ENDPOINT = "https://scione.lingxing.com";

    // 查询质检模板列表接口
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
