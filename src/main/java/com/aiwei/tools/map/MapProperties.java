package com.aiwei.tools.map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高德地图工具配置。
 *
 * @param apiKey 高德 Web Service Key
 * @param baseUrl 高德 REST API 根地址
 * @param timeoutMs 单次请求超时毫秒数
 */
@ConfigurationProperties(prefix = "aiwei.tools.map")
public record MapProperties(
        String apiKey,
        String baseUrl,
        int timeoutMs) {

    /**
     * 规范化可空配置。
     */
    public MapProperties {
        apiKey = apiKey == null ? "" : apiKey.trim();
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://restapi.amap.com"
                : baseUrl.replaceAll("/+$", "");
        timeoutMs = timeoutMs <= 0 ? 10000 : timeoutMs;
    }
}
