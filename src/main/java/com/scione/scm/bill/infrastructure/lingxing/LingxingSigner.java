package com.scione.scm.bill.infrastructure.lingxing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;

/**
 * 与 lingxing-data-sync 保持一致的领星 OpenAPI 签名实现。
 */
@Component
@RequiredArgsConstructor
public class LingxingSigner {

    private final ObjectMapper objectMapper;

    public String sign(Map<String, ?> parameters, String aesKey) {
        String plainText = parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + stringify(entry.getValue()).trim())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(plainText.getBytes(StandardCharsets.UTF_8));
            String md5Hex = HexFormat.of().withUpperCase().formatHex(digest);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey.getBytes(StandardCharsets.UTF_8), "AES"));
            return Base64.getEncoder().encodeToString(
                    cipher.doFinal(md5Hex.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法生成领星 OpenAPI 请求签名", exception);
        }
    }

    private String stringify(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Boolean bool) {
            return bool.toString().toLowerCase();
        }
        if (value instanceof Map<?, ?> || value instanceof Collection<?> || value.getClass().isArray()) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("无法序列化领星 OpenAPI 签名参数", exception);
            }
        }
        return value.toString();
    }
}
