package com.aiwei.tools.music;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HIFIVE 音加加配置。
 *
 * @param gateway API 网关
 * @param appId 应用 ID
 * @param serverCode 服务端密钥
 * @param version API 版本
 * @param timeoutMs 请求超时
 */
@ConfigurationProperties(prefix = "aiwei.tools.hifive")
public record HifiveProperties(
        String gateway,
        String appId,
        String serverCode,
        String version,
        long timeoutMs) {

    /**
     * 规范化默认配置。
     */
    public HifiveProperties {
        gateway = gateway == null || gateway.isBlank()
                ? "https://gateway-open.haifanwu.com" : gateway.trim();
        appId = appId == null ? "" : appId.trim();
        serverCode = serverCode == null ? "" : serverCode.trim();
        version = version == null || version.isBlank() ? "V4.2.0" : version.trim();
        timeoutMs = timeoutMs > 0 ? timeoutMs : 8000;
    }
}
