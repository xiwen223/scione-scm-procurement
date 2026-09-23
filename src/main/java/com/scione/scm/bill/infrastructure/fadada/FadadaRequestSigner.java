package com.scione.scm.bill.infrastructure.fadada;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 法大大 V5 请求签名器。
 *
 * <p>签名顺序为：参数按 key 排序并拼接 -> SHA-256 ->
 * HMAC-SHA256(appSecret, timestamp) -> HMAC-SHA256(临时密钥, signText)。</p>
 */
@Component
public class FadadaRequestSigner {

    public String sign(Map<String, String> parameters, String timestamp, String appSecret) {
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException("法大大 AppSecret 为空");
        }
        String parameterText = new TreeMap<>(parameters).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        try {
            String signText = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(parameterText.getBytes(StandardCharsets.UTF_8)));
            byte[] secretSigning = hmacSha256(
                    appSecret.getBytes(StandardCharsets.UTF_8), timestamp.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacSha256(
                    secretSigning, signText.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法生成法大大 OpenAPI 请求签名", exception);
        }
    }

    private byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
