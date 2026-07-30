package com.aiwei.tools.flight;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * 飞猪 TOP 请求签名器。
 */
final class AlitripSigner {

    private AlitripSigner() {
    }

    static String sign(Map<String, String> params, String secret, String method) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((key, value) -> {
            if (!"sign".equals(key) && value != null) {
                canonical.append(key).append(value);
            }
        });
        return "hmac".equalsIgnoreCase(method)
                ? hmacMd5(canonical.toString(), secret)
                : md5(secret + canonical + secret);
    }

    private static String md5(String input) {
        try {
            return hex(MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("MD5 sign failed", error);
        }
    }

    private static String hmacMd5(String input, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacMD5"));
            return hex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("HMAC sign failed", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02X", value));
        }
        return result.toString();
    }
}
