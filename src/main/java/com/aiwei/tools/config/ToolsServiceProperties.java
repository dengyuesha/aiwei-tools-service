package com.aiwei.tools.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具服务的公共运行配置。
 *
 * @param apiKey 服务间调用密钥；为空时关闭鉴权，仅用于本地开发
 * @param defaultTimeoutMs 未单独声明超时时使用的默认毫秒数
 */
@ConfigurationProperties(prefix = "aiwei.tools")
public record ToolsServiceProperties(String apiKey, long defaultTimeoutMs) {

    /**
     * 规范化配置默认值。
     */
    public ToolsServiceProperties {
        apiKey = apiKey == null ? "" : apiKey.trim();
        defaultTimeoutMs = defaultTimeoutMs > 0 ? defaultTimeoutMs : 8000L;
    }
}

