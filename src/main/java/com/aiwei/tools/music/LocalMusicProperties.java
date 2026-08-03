package com.aiwei.tools.music;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内置本地音乐配置。
 *
 * @param enabled 是否优先使用内置音乐
 * @param publicBaseUrl 音频静态资源对设备端可访问的服务根地址
 */
@ConfigurationProperties(prefix = "aiwei.tools.local-music")
public record LocalMusicProperties(boolean enabled, String publicBaseUrl) {

    /**
     * 规范化本地音乐配置。
     */
    public LocalMusicProperties {
        publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
    }
}
