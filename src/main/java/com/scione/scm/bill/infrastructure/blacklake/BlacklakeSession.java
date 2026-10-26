package com.scione.scm.bill.infrastructure.blacklake;

import org.springframework.web.client.RestClient;

/** 一次登录的独立会话，内部客户端维护 Cookie；不生成包含 Token 的 toString。 */
public final class BlacklakeSession {
    private final String token;
    private final RestClient restClient;

    BlacklakeSession(String token, RestClient restClient) {
        this.token = token;
        this.restClient = restClient;
    }

    String token() {
        return token;
    }

    RestClient restClient() {
        return restClient;
    }
}
