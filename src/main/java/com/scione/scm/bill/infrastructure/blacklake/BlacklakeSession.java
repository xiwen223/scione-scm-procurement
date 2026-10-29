package com.scione.scm.bill.infrastructure.blacklake;

import org.springframework.web.client.RestClient;

/** 一次登录的独立会话，内部客户端维护 Cookie；不生成包含 Token 的 toString。 */
public final class BlacklakeSession {
    private final String token;
    private final RestClient restClient;

    /**
     * 封装一次认证会话的Token及维护Cookie的HTTP客户端。
     *
     * @param token 当前请求使用的认证Token；登录请求可为null
     * @param restClient 当前黑湖会话的HTTP客户端
     */
    BlacklakeSession(String token, RestClient restClient) {
        this.token = token;
        this.restClient = restClient;
    }

    /**
     * 取得本会话发送X-AUTH请求头时使用的Token。
     *
     * @return 本会话的认证Token，不得写入日志
     */
    String token() {
        return token;
    }

    /**
     * 取得本会话使用的HTTP客户端。
     *
     * @return 维护本会话Cookie的HTTP客户端
     */
    RestClient restClient() {
        return restClient;
    }
}
