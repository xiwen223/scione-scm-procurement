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
    private final SopReminderSourceRepository repository;//读同步库里的网页登录Cookie
    private final ObjectMapper objectMapper;//解析Cookie JSON和店铺响应
    // 禁止跟随跳转，避免Cookie被发送到登录跳转页或其他域名。
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    /** 店铺ID与名称直接由客户端返回，不再为唯一实现维护一层转发接口。 */
    public record Seller(long sid, String name) { }

    /** 调领星网页接口，返回当前登录账号可见的店铺列表（sid+名称），供节点1筛杭州店铺用。 */
    public List<Seller> findSellers() {
        try {
            JsonNode cached = objectMapper.readTree(repository.readSellerCookies());//从同步库里读出的网页Cookie JSON数组
            if (!cached.isArray()) throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "店铺Cookie格式异常");//Cookie必须是数组结构
            Map<String, String> cookies = new LinkedHashMap<>();//过滤后的Cookie：key=Cookie名，value=Cookie值
            for (JsonNode cookie : cached) {//逐个Cookie处理
                String domain = cookie.path("domain").asText();//该Cookie适用的域名
                // 只使用适用于scione.lingxing.com的Cookie，不拼入其他网站Cookie。
                if (!Set.of("scione.lingxing.com", ".scione.lingxing.com", "lingxing.com", ".lingxing.com").contains(domain)) continue;//域名不在领星白名单里，跳过
                String name = cookie.path("name").asText();//Cookie名
                String value = cookie.path("value").asText();//Cookie值
                if (!name.matches("[A-Za-z0-9_-]+") || value.matches("(?s).*[\\r\\n;].*")) {
                    throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "店铺Cookie包含非法字符");//Cookie名含特殊字符或值含换行/分号，可能是脏数据，停止
                }
                cookies.put(name, value);//存进过滤后的map
            }
            String token = cookies.getOrDefault("authToken", cookies.getOrDefault("auth-token", cookies.get("token")));//登录令牌：优先authToken，其次auth-token，最后token
            if (token == null || token.isBlank()) throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "缺少店铺网页登录令牌，请先运行login任务");//没令牌说明没登录，停
            StringJoiner header = new StringJoiner("; ");//把Cookie拼成"name=value; name2=value2"的请求头格式
            cookies.forEach((name, value) -> header.add(name + "=" + value));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://scione.lingxing.com/api/module/scmPlatformStore/PlatformStore/getStoreDropDown"))//领星店铺下拉接口地址
                    .timeout(Duration.ofSeconds(30)).header("Cookie", header.toString())//30秒超时 + 带上拼好的Cookie
                    .header("Auth-Token", token).header("Accept", "application/json")//登录令牌 + 声明要JSON
                    .header("Referer", "https://scione.lingxing.com/")//来源页，防反爬
                    .header("Ak-Client-Type", "web").header("X-Ak-Request-Source", "erp")//领星要求的客户端类型标识
                    .header("X-Ak-Company-Id", cookies.getOrDefault("company_id", ""))//公司ID，从Cookie取
                    .header("X-Ak-Uid", cookies.getOrDefault("uid", ""))//用户ID，从Cookie取
                    .header("X-Ak-Zid", cookies.getOrDefault("zid", "1"))//组织ID，默认1
                    .header("X-Ak-Env-Key", cookies.getOrDefault("env_key", "scione"))//环境标识，默认scione
                    .header("Content-Type", "application/json")//请求体是JSON
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();//POST空对象请求体
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());//发起请求，拿字符串响应
            if (response.statusCode() != 200) throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺查询失败，HTTP " + response.statusCode());//非200直接报错
            return parseStores(objectMapper.readTree(response.body()));//把响应体解析成店铺列表
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺查询已中断");//线程被中断，恢复中断标记后报错
        } catch (java.io.IOException exception) {
            // 不暴露原始响应、Cookie或请求头。
            throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺请求或响应解析失败，请检查登录状态");
        }
    }
    /** 抓包响应为data.sellers[].stores[]；markets只是站点字典，不是店铺列表。 */
    static List<Seller> parseStores(JsonNode root) {
        if (root == null || !"1".equals(root.path("code").asText())
                || !root.path("data").path("sellers").isArray()) {
            throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺查询未成功，请检查登录状态和账号权限");//响应code不为1或sellers不是数组，说明接口失败
        }
        List<Seller> result = new ArrayList<>();//最终返回的店铺列表
        Set<Long> seen = new HashSet<>();//记录已见过的sid，用于去重
        for (JsonNode platform : root.path("data").path("sellers")) {//遍历每个seller（可能对应一个平台/站点）
            if (!platform.path("stores").isArray()) {
                throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺列表结构不完整");//该seller下没有stores数组，结构异常
            }
            for (JsonNode row : platform.path("stores")) {//遍历该seller下的每个店铺
                // store_id直接对应PP.sid；共享店铺是18位ID，必须按long解析，禁止经过double。
                String rawId = row.path("store_id").asText();//店铺ID原文（字符串）
                long sid;//解析后的店铺ID
                try {
                    if (!rawId.matches("[0-9]+")) throw new NumberFormatException();//不是纯数字直接抛
                    sid = Long.parseLong(rawId);//按long解析，避免18位大ID被double丢精度
                } catch (NumberFormatException exception) {
                    throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺返回无效ID");
                }
                JsonNode nameNode = row.path("store_name");//店铺名称节点
                if (sid <= 0 || !nameNode.isTextual() || nameNode.asText().isBlank() || !seen.add(sid)) {
                    throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺返回无效名称或重复ID");//ID非法、名称非文本/为空、或ID重复，都算异常
                }
                // 包括store_type=4的NA/EU共享店铺；杭州筛选仍由节点1Service执行。
                result.add(new Seller(sid, nameNode.asText()));//把sid+名称存进结果
            }
        }
        if (result.isEmpty()) throw new BusinessException(ResultCode.SOP_PREVIEW_UNAVAILABLE, "网页店铺范围为空，停止催办预览");//一个店铺都没有，停
        return List.copyOf(result);//返回不可变列表
    }

}
