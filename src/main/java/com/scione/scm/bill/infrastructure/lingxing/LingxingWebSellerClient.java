package com.scione.scm.bill.infrastructure.lingxing;

import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.domain.sop.SopReminderSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/** 使用已抓包确认的网页接口；仅返回当前登录账号有权限看到的店铺。 */
@Component
@RequiredArgsConstructor
public class LingxingWebSellerClient {
    private final SopReminderSourceRepository repository;
    private final ObjectMapper objectMapper;
    // 禁止跟随跳转，避免Cookie被发送到登录跳转页或其他域名。
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    /** 店铺ID与名称直接由客户端返回，不再为唯一实现维护一层转发接口。 */
    public record Seller(long sid, String name) { }

    public List<Seller> findSellers() {
        try {
            JsonNode cached = objectMapper.readTree(repository.readSellerCookies());
            if (!cached.isArray()) throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "店铺Cookie格式异常");
            Map<String, String> cookies = new LinkedHashMap<>();
            for (JsonNode cookie : cached) {
                String domain = cookie.path("domain").asText();
                // 只使用适用于scione.lingxing.com的Cookie，不拼入其他网站Cookie。
                if (!Set.of("scione.lingxing.com", ".scione.lingxing.com", "lingxing.com", ".lingxing.com").contains(domain)) continue;
                String name = cookie.path("name").asText();
                String value = cookie.path("value").asText();
                if (!name.matches("[A-Za-z0-9_-]+") || value.matches("(?s).*[\\r\\n;].*")) {
                    throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "店铺Cookie包含非法字符");
                }
                cookies.put(name, value);
            }
            String token = cookies.getOrDefault("authToken", cookies.getOrDefault("auth-token", cookies.get("token")));
            if (token == null || token.isBlank()) throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "缺少店铺网页登录令牌，请先运行login任务");
            StringJoiner header = new StringJoiner("; ");
            cookies.forEach((name, value) -> header.add(name + "=" + value));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://scione.lingxing.com/api/module/scmPlatformStore/PlatformStore/getStoreDropDown"))
                    .timeout(Duration.ofSeconds(30)).header("Cookie", header.toString())
                    .header("Auth-Token", token).header("Accept", "application/json")
                    .header("Referer", "https://scione.lingxing.com/")
                    .header("Ak-Client-Type", "web").header("X-Ak-Request-Source", "erp")
                    .header("X-Ak-Company-Id", cookies.getOrDefault("company_id", ""))
                    .header("X-Ak-Uid", cookies.getOrDefault("uid", ""))
                    .header("X-Ak-Zid", cookies.getOrDefault("zid", "1"))
                    .header("X-Ak-Env-Key", cookies.getOrDefault("env_key", "scione"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺查询失败，HTTP " + response.statusCode());
            return parseStores(objectMapper.readTree(response.body()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺查询已中断");
        } catch (java.io.IOException exception) {
            // 不暴露原始响应、Cookie或请求头。
            throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺请求或响应解析失败，请检查登录状态");
        }
    }
    /** 抓包响应为data.sellers[].stores[]；markets只是站点字典，不是店铺列表。 */
    static List<Seller> parseStores(JsonNode root) {
        if (root == null || !"1".equals(root.path("code").asText())
                || !root.path("data").path("sellers").isArray()) {
            throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺查询未成功，请检查登录状态和账号权限");
        }
        List<Seller> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (JsonNode platform : root.path("data").path("sellers")) {
            if (!platform.path("stores").isArray()) {
                throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺列表结构不完整");
            }
            for (JsonNode row : platform.path("stores")) {
                // store_id直接对应PP.sid；共享店铺是18位ID，必须按long解析，禁止经过double。
                String rawId = row.path("store_id").asText();
                long sid;
                try {
                    if (!rawId.matches("[0-9]+")) throw new NumberFormatException();
                    sid = Long.parseLong(rawId);
                } catch (NumberFormatException exception) {
                    throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺返回无效ID");
                }
                JsonNode nameNode = row.path("store_name");
                if (sid <= 0 || !nameNode.isTextual() || nameNode.asText().isBlank() || !seen.add(sid)) {
                    throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺返回无效名称或重复ID");
                }
                // 包括store_type=4的NA/EU共享店铺；杭州筛选仍由节点1Service执行。
                result.add(new Seller(sid, nameNode.asText()));
            }
        }
        if (result.isEmpty()) throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺范围为空，停止催办预览");
        return List.copyOf(result);
    }

}
