package com.scione.scm.bill.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 仅对日志展示的响应脱敏，不修改业务响应及失败落库内容。 */
public final class ExternalApiResponseSanitizer {
    private static final Set<String> SECRET_FIELDS = Set.of("token", "accesstoken", "refreshtoken",
            "authtoken", "password", "passwd", "pwd", "appsecret", "secret", "authorization",
            "cookie", "setcookie", "xauth", "sign", "signature");

    private ExternalApiResponseSanitizer() {
    }

    public static String sanitize(String path, String responseBody, ObjectMapper objectMapper) {
        String printableResponse = "<空响应>";
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode response = objectMapper.readTree(responseBody);
                // 黑湖登录的data直接为令牌，没有独立token字段。
                if ("/api/user/v1/users/_login".equals(path) && response instanceof ObjectNode object) {
                    object.put("data", "***");
                }
                maskSecrets(response);
                printableResponse = response.toString();
            } catch (JsonProcessingException exception) {
                printableResponse = "<非JSON响应，避免泄露凭证，正文不打印>";
            }
        }
        return printableResponse;
    }

    private static void maskSecrets(JsonNode response) {
        if (response instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalizedName = field.getKey().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                if (SECRET_FIELDS.contains(normalizedName)) {
                    object.put(field.getKey(), "***");
                } else {
                    maskSecrets(field.getValue());
                }
            }
        } else if (response != null && response.isArray()) {
            for (JsonNode item : response) {
                maskSecrets(item);
            }
        }
    }
}
