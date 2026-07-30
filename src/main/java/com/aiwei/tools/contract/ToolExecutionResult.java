package com.aiwei.tools.contract;

import java.util.Map;

/**
 * 工具执行器返回的中立结果。
 *
 * @param provider 实际数据或执行供应商
 * @param summary 用户可读摘要
 * @param data 结构化领域数据
 * @param cached 是否来自缓存
 */
public record ToolExecutionResult(
        String provider,
        String summary,
        Map<String, Object> data,
        boolean cached) {

    /**
     * 规范化可空字段。
     */
    public ToolExecutionResult {
        provider = provider == null ? "" : provider;
        summary = summary == null ? "" : summary;
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}

