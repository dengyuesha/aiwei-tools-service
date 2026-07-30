package com.aiwei.tools.contract;

/**
 * 工具响应元数据。
 *
 * @param requestId 请求 ID
 * @param elapsedMs 服务端执行耗时
 * @param cached 是否来自缓存
 */
public record ToolResponseMetadata(String requestId, long elapsedMs, boolean cached) {
}

