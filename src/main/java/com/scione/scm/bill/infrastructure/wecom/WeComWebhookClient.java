package com.scione.scm.bill.infrastructure.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final String ENDPOINT = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=";
    private static final Set<Integer> REJECTED_HTTP = Set.of(400, 401, 403, 404, 405, 413, 415, 422, 429);
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WeComWebhookClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 与原数据库状态兼容：0未发送、2成功、3失败、4未知；1仅表示正在执行。 */
    public enum SendState {
        UNSENT(0), SUCCESS(2), FAILED(3), UNKNOWN(4);
        private final int databaseValue;
        SendState(int databaseValue) { this.databaseValue = databaseValue; }
        public int databaseValue() { return databaseValue; }
    }

    public record SendOutcome(SendState state, Integer errorCode, Integer httpStatus,
                              String stage, boolean retryable, String reason) {
        public boolean success() { return state == SendState.SUCCESS; }
    }

    /** 群机器人按手机号@人；企微成员必有手机号，未配置手机号视为不可发送。 */
    public SendOutcome sendText(String webhookKey, String content, List<String> mentionedMobiles) {
        HttpRequest request;
        try {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("content", content);
            if (mentionedMobiles != null && !mentionedMobiles.isEmpty()) text.put("mentioned_mobile_list", mentionedMobiles);
            String body = objectMapper.writeValueAsString(Map.of("msgtype", "text", "text", text));
            request = HttpRequest.newBuilder(URI.create(ENDPOINT + webhookKey)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        } catch (IOException | RuntimeException exception) {
            // 请求构建前失败可以证明未发送，但参数/序列化错误需要修正，不盲目重试。
            return new SendOutcome(SendState.UNSENT, null, null, "BUILD", false, exception.getClass().getSimpleName());
        }
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return classifyResponse(response.statusCode(), response.body());
        } catch (HttpConnectTimeoutException | ConnectException | UnknownHostException exception) {
            return new SendOutcome(SendState.UNSENT, null, null, "CONNECT", true, exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unknown(null, "INTERRUPTED", "InterruptedException");
        } catch (IOException | RuntimeException exception) {
            // 普通IOException可能发生在响应读取阶段，企微可能已经发出消息，不能安全重发。
            return unknown(null, "EXCHANGE", exception.getClass().getSimpleName());
        }
    }

    SendOutcome classifyResponse(int httpStatus, String body) {
        if (httpStatus != 200) {
            // 仅明确拒绝类HTTP状态归失败；502/504等不能证明上游没有处理。
            if (REJECTED_HTTP.contains(httpStatus)) {
                return new SendOutcome(SendState.FAILED, null, httpStatus, "HTTP", httpStatus == 429, "HTTP_REJECTED");
            }
            return unknown(httpStatus, "HTTP", "HTTP_UNCERTAIN");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.path("errcode").isIntegralNumber() || !node.path("errcode").canConvertToInt()) {
                return unknown(httpStatus, "RESPONSE", "INVALID_RESPONSE");
            }
            int code = node.path("errcode").asInt();
            // 未验证的业务错误不自动重试；需要先确认该错误码允许重试。
            return new SendOutcome(code == 0 ? SendState.SUCCESS : SendState.FAILED,
                    code, httpStatus, "BUSINESS", false, code == 0 ? "OK" : "BUSINESS_REJECTED");
        } catch (IOException | RuntimeException exception) {
            return unknown(httpStatus, "RESPONSE", "INVALID_RESPONSE");
        }
    }

    private static SendOutcome unknown(Integer httpStatus, String stage, String reason) {
        // 不保存可能包含webhook key的异常原文和URL。
        return new SendOutcome(SendState.UNKNOWN, null, httpStatus, stage, false, reason);
    }
}
