package com.aiwei.tools.memory;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仅汇总调用方显式传入的记忆，避免跨用户读取或虚构画像。
 */
@Component
public class MemoryDigestToolExecutor implements ToolExecutor {

    @Override
    public String toolName() {
        return "memory.digest";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        List<String> memories = memories(request.arguments().get("memories"));
        Map<String, Object> profile = profile(request.arguments().get("profile"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "memory_digest");
        data.put("profile", profile);
        data.put("memory_count", memories.size());
        data.put("recent_memories", memories.stream().limit(8).toList());
        data.put("privacy", "只处理本次请求显式传入的数据，不读取其他会话或用户记忆。");
        String summary = memories.isEmpty()
                ? "本次请求没有提供可汇总的记忆。"
                : "已汇总本次提供的 " + memories.size() + " 条记忆。";
        return new ToolExecutionResult("request_scoped_memory_digest", summary, data, false);
    }

    private List<String> memories(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).map(String::trim)
                .filter(item -> !item.isBlank()).toList();
    }

    private Map<String, Object> profile(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
