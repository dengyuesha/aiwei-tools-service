package com.aiwei.tools.state;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具服务自有状态存储配置。
 *
 * @param dataDirectory 租户隔离 JSONL 数据目录
 */
@ConfigurationProperties(prefix = "aiwei.tools.state")
public record StateProperties(String dataDirectory) {

    /**
     * 规范化默认目录。
     */
    public StateProperties {
        dataDirectory = dataDirectory == null || dataDirectory.isBlank()
                ? "./data/tool-state" : dataDirectory.trim();
    }
}
