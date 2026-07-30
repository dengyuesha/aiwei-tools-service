package com.aiwei.tools.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 联网搜索配置。
 *
 * @param baiduApiKey 百度 AI 搜索密钥
 * @param baiduEndpoint 百度 AI 搜索地址
 * @param baiduSource 搜索源
 * @param duckduckgoEndpoint DuckDuckGo 即时答案地址
 * @param timeoutMs 超时毫秒数
 * @param fetchMaxChars 网页正文最大字符数
 */
@ConfigurationProperties(prefix = "aiwei.tools.web")
public record SearchProperties(
        String baiduApiKey,
        String baiduEndpoint,
        String baiduSource,
        String duckduckgoEndpoint,
        int timeoutMs,
        int fetchMaxChars) {

    /**
     * 规范化配置默认值。
     */
    public SearchProperties {
        baiduApiKey = text(baiduApiKey);
        baiduEndpoint = defaultValue(baiduEndpoint,
                "https://qianfan.baidubce.com/v2/ai_search/chat/completions");
        baiduSource = defaultValue(baiduSource, "baidu_search_v2");
        duckduckgoEndpoint = defaultValue(duckduckgoEndpoint, "https://api.duckduckgo.com/");
        timeoutMs = timeoutMs <= 0 ? 10000 : timeoutMs;
        fetchMaxChars = fetchMaxChars <= 0 ? 20000 : Math.min(fetchMaxChars, 100000);
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
