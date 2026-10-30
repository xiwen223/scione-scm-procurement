package com.scione.scm.bill.infrastructure.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.infrastructure.wecom.enums.SendState;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 企微客户端只分类一次请求结果；重试次数、时间和持久化由应用层控制。 */
@Component
public class WeComWebhookClient {
    private static final String ENDPOINT = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=";//企微群机器人地址，key拼接在后面
    private static final Set<Integer> REJECTED_HTTP = Set.of(400, 401, 403, 404, 405, 413, 415, 422, 429);//明确拒绝类HTTP状态，归失败不重试
    private final ObjectMapper objectMapper;//请求体/响应体JSON解析
    private final HttpClient httpClient;//10秒连接超时的HTTP客户端

    public WeComWebhookClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 发送结果状态枚举已迁移至独立枚举类：com.scione.scm.bill.infrastructure.wecom.enums.SendState。 */
    public record SendOutcome(SendState state, Integer errorCode, Integer httpStatus,
                              String stage, boolean retryable, String reason) {
        public boolean success() { return state == SendState.SUCCESS; }
    }

    /** 群机器人按手机号@人；企微成员必有手机号，未配置手机号视为不可发送。 */
    public SendOutcome sendText(String webhookKey, String content, List<String> mentionedMobiles) {
        HttpRequest request;
        try {
            Map<String, Object> text = new LinkedHashMap<>();//企微text消息体
            text.put("content", content);//消息内容
            if (mentionedMobiles != null && !mentionedMobiles.isEmpty()) text.put("mentioned_mobile_list", mentionedMobiles);//要@的手机号列表（可选）
            String body = objectMapper.writeValueAsString(Map.of("msgtype", "text", "text", text));//拼成企微要求的JSON：{"msgtype":"text","text":{...}}
            request = HttpRequest.newBuilder(URI.create(ENDPOINT + webhookKey)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();//构建POST请求，15秒超时
        } catch (IOException | RuntimeException exception) {
            // 请求构建前失败可以证明未发送，但参数/序列化错误需要修正，不盲目重试。
            return new SendOutcome(SendState.UNSENT, null, null, "BUILD", false, exception.getClass().getSimpleName());//构建请求就失败了，肯定没发出去
        }
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));//真正发请求
            return classifyResponse(response.statusCode(), response.body());//按HTTP状态和响应体分类结果
        } catch (HttpConnectTimeoutException | ConnectException | UnknownHostException exception) {
            return new SendOutcome(SendState.UNSENT, null, null, "CONNECT", true, exception.getClass().getSimpleName());//连接超时/连不上/域名解析失败，明确没发出去，可重试
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unknown(null, "INTERRUPTED", "InterruptedException");//线程中断，结果未知
        } catch (IOException | RuntimeException exception) {
            // 普通IOException可能发生在响应读取阶段，企微可能已经发出消息，不能安全重发。
            return unknown(null, "EXCHANGE", exception.getClass().getSimpleName());//读取响应阶段异常，可能已发出，结果未知
        }
    }

    /** 根据HTTP状态码和企微响应体，把一次发送结果分成 成功/失败/未知 三类。 */
    SendOutcome classifyResponse(int httpStatus, String body) {
        if (httpStatus != 200) {
            // 仅明确拒绝类HTTP状态归失败；502/504等不能证明上游没有处理。
            if (REJECTED_HTTP.contains(httpStatus)) {
                return new SendOutcome(SendState.FAILED, null, httpStatus, "HTTP", httpStatus == 429, "HTTP_REJECTED");//明确拒绝（4xx/429等），失败，429可重试
            }
            return unknown(httpStatus, "HTTP", "HTTP_UNCERTAIN");//502/504等不确定状态，归未知
        }
        try {
            JsonNode node = objectMapper.readTree(body);//解析响应体JSON
            if (node == null || !node.path("errcode").isIntegralNumber() || !node.path("errcode").canConvertToInt()) {
                return unknown(httpStatus, "RESPONSE", "INVALID_RESPONSE");//没有errcode字段或不是整数，响应异常，归未知
            }
            int code = node.path("errcode").asInt();//取企微返回的业务错误码
            // 未验证的业务错误不自动重试；需要先确认该错误码允许重试。
            return new SendOutcome(code == 0 ? SendState.SUCCESS : SendState.FAILED,
                    code, httpStatus, "BUSINESS", false, code == 0 ? "OK" : "BUSINESS_REJECTED");//errcode=0成功，否则业务失败
        } catch (IOException | RuntimeException exception) {
            return unknown(httpStatus, "RESPONSE", "INVALID_RESPONSE");//响应体解析失败，归未知
        }
    }

    /** 构造"结果未知"的返回，不保存可能含webhook key的异常原文和URL。 */
    private static SendOutcome unknown(Integer httpStatus, String stage, String reason) {
        // 不保存可能包含webhook key的异常原文和URL。
        return new SendOutcome(SendState.UNKNOWN, null, httpStatus, stage, false, reason);
    }
}
