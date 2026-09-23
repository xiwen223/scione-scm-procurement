package com.scione.scm.bill.application.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 法大大企业授权状态，对应 {@code /corp/get} 响应中的 data 节点。
 *
 * <p>前端用 {@code clientCorpName} 与 {@code openCorpId} 回显公司名称与法大大公司 ID，
 * 其余状态字段用于展示企业的绑定 / 认证 / 可用情况。</p>
 */
public record FadadaCorpAuthStatusResponse(
        String clientCorpId,
        String clientCorpName,
        String bindingStatus,
        String identStatus,
        String availableStatus,
        String openCorpId,
        List<String> authScope) {

    /** 从法大大原始响应组装；缺失字段保持为 null，authScope 缺失时返回空列表。 */
    public static FadadaCorpAuthStatusResponse from(JsonNode data) {
        List<String> scopes = new ArrayList<>();
        JsonNode authScope = data == null ? null : data.path("authScope");
        if (authScope != null && authScope.isArray()) {
            authScope.forEach(scope -> scopes.add(scope.asText()));
        }
        return new FadadaCorpAuthStatusResponse(
                text(data, "clientCorpId"),
                text(data, "clientCorpName"),
                text(data, "bindingStatus"),
                text(data, "identStatus"),
                text(data, "availableStatus"),
                text(data, "openCorpId"),
                List.copyOf(scopes));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
