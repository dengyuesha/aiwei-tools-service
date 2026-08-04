package com.aiwei.tools.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * 海搜开放 API 配置。
 *
 * @param apiKey iDataRiver API Key
 * @param searchEndpoint 搜索接口
 * @param validateEndpoint 分享检测接口
 * @param timeoutMs 单次调用超时
 * @param dailyFreeLimit 每日免费调用硬上限
 * @param quotaZone 供应商计费日时区
 * @param quotaFile 持久化调用计数文件
 */
@ConfigurationProperties(prefix = "aiwei.tools.haisou")
public record HaisouProperties(
        String apiKey,
        String searchEndpoint,
        String validateEndpoint,
        int timeoutMs,
        int dailyFreeLimit,
        String quotaZone,
        Path quotaFile) {

    /**
     * 规范化配置，并将调用上限限制在公开免费档的 100 次以内。
     */
    public HaisouProperties {
        apiKey = text(apiKey);
        searchEndpoint = value(searchEndpoint, "https://apiok.us/api/b9d1/search");
        validateEndpoint = value(validateEndpoint, "https://apiok.us/api/b9d1/validate");
        timeoutMs = timeoutMs <= 0 ? 12000 : Math.min(timeoutMs, 60000);
        dailyFreeLimit = dailyFreeLimit <= 0 ? 100 : Math.min(dailyFreeLimit, 100);
        quotaZone = validZone(quotaZone);
        quotaFile = quotaFile == null ? Path.of("./data/haisou-quota.json") : quotaFile;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String value(String input, String fallback) {
        return input == null || input.isBlank() ? fallback : input.trim();
    }

    private static String validZone(String input) {
        String candidate = value(input, "UTC");
        try {
            ZoneId.of(candidate);
            return candidate;
        } catch (DateTimeException ignored) {
            return "UTC";
        }
    }
}
