package com.aiwei.tools.flight;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 航班多供应商配置。
 *
 * @param provider auto、variflight、alitrip 或 juhe
 * @param variflightApiKey 飞常准 API Key
 * @param variflightUrl 飞常准 MCP 数据地址
 * @param alitripAppKey 飞猪应用 Key
 * @param alitripAppSecret 飞猪应用密钥
 * @param alitripGateway 飞猪 TOP 网关
 * @param alitripSignMethod 飞猪签名算法
 * @param juheApiKey 聚合航班 API Key
 * @param juheUrl 聚合航班查询地址
 * @param timeoutMs 单供应商请求超时
 */
@ConfigurationProperties(prefix = "aiwei.tools.flight")
public record FlightProperties(
        String provider,
        String variflightApiKey,
        String variflightUrl,
        String alitripAppKey,
        String alitripAppSecret,
        String alitripGateway,
        String alitripSignMethod,
        String juheApiKey,
        String juheUrl,
        long timeoutMs) {

    /**
     * 规范化航班配置默认值。
     */
    public FlightProperties {
        provider = text(provider, "auto").toLowerCase();
        variflightApiKey = text(variflightApiKey, "");
        variflightUrl = text(variflightUrl, "https://mcp.variflight.com/api/v1/mcp/data");
        alitripAppKey = text(alitripAppKey, "");
        alitripAppSecret = text(alitripAppSecret, "");
        alitripGateway = text(alitripGateway, "https://eco.taobao.com/router/rest");
        alitripSignMethod = text(alitripSignMethod, "md5");
        juheApiKey = text(juheApiKey, "");
        juheUrl = text(juheUrl, "");
        timeoutMs = timeoutMs > 0 ? timeoutMs : 10000L;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
