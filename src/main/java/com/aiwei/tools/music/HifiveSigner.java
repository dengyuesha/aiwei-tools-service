package com.aiwei.tools.music;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * HIFIVE HF3-HMAC-SHA1 请求签名器。
 */
final class HifiveSigner {

    private HifiveSigner() {
    }

    static String sign(String method, String action, HifiveProperties properties,
                       String nonce, String clientId, String timestamp,
                       Map<String, String> params) throws Exception {
        String canonical = params == null ? "" : new TreeMap<>(params).entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        String publicPart = String.join(" ", method, action, properties.version(),
                properties.appId(), nonce, clientId, "HF3-HMAC-SHA1", timestamp);
        String publicBase64 = Base64.getEncoder()
                .encodeToString(publicPart.getBytes(StandardCharsets.UTF_8));
        String source = canonical.isBlank() ? publicBase64 : canonical + "&" + publicBase64;
        String signingBase = Base64.getEncoder()
                .encodeToString(source.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(properties.serverCode().getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] hmac = mac.doFinal(signingBase.getBytes(StandardCharsets.UTF_8));
        byte[] digest = MessageDigest.getInstance("MD5").digest(hmac);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            hex.append(String.format("%02X", item));
        }
        return hex.toString();
    }
}
