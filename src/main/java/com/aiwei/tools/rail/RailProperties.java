package com.aiwei.tools.rail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 聚合火车票数据源配置。
 *
 * @param apiKey 聚合数据 API Key
 * @param endpoint 火车票查询地址
 * @param timeoutMs 单次上游请求超时
 * @param maxDaysAhead 允许查询的最远天数
 */
@ConfigurationProperties(prefix = "aiwei.tools.rail")
public record RailProperties(
        String apiKey,
        String endpoint,
        long timeoutMs,
        int maxDaysAhead) {

    /**
     * 规范化火车票配置默认值。
     */
    public RailProperties {
        apiKey = apiKey == null ? "" : apiKey.trim();
        endpoint = endpoint == null || endpoint.isBlank()
                ? "https://apis.juhe.cn/fapigw/train/query"
                : endpoint.trim();
        timeoutMs = timeoutMs > 0 ? timeoutMs : 10000L;
        maxDaysAhead = maxDaysAhead > 0 ? maxDaysAhead : 15;
    }
}

