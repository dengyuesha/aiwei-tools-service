package com.aiwei.tools.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一工具调用请求。
 *
 * @param requestId 跨服务追踪 ID
 * @param tenantId 租户 ID
 * @param userId 用户 ID
 * @param sessionId 会话 ID
 * @param arguments 工具参数
 * @param context 设备和语言上下文
 * @param idempotencyKey 有副作用工具的幂等键
 */
public record ToolInvokeRequest(
        String requestId,
        String tenantId,
        String userId,
        String sessionId,
        Map<String, Object> arguments,
        ToolContext context,
        String idempotencyKey) {

    /**
     * 把可空集合和上下文规范化为只读安全值。
     */
    public ToolInvokeRequest {
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        context = context == null ? ToolContext.empty() : context;
    }
}
