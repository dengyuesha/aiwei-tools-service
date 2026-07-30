package com.aiwei.tools.state;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 在当前租户和用户范围内检索基础记忆。
 */
@Component
public class MemorySearchToolExecutor implements ToolExecutor {

    private final TenantStateRepository repository;

    /**
     * 创建记忆检索执行器。
     *
     * @param repository 状态仓库
     */
    public MemorySearchToolExecutor(TenantStateRepository repository) {
        this.repository = repository;
    }

    @Override
    public String toolName() {
        return "mcp.memory.search";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String query = String.valueOf(request.arguments().getOrDefault("query", "")).trim();
        int limit = limit(request.arguments().get("limit"));
        List<Map<String, Object>> items = repository.list("memories", request).stream()
                .map(item -> Map.entry(item, score(item, query)))
                .filter(entry -> query.isBlank() || entry.getValue() > 0)
                .sorted(Map.Entry.<Map<String, Object>, Integer>comparingByValue(
                        Comparator.reverseOrder()))
                .limit(limit).map(Map.Entry::getKey).toList();
        return new ToolExecutionResult("tenant_jsonl_memory",
                items.isEmpty() ? "没有找到相关记忆。" : "找到 " + items.size() + " 条相关记忆。",
                Map.of("type", "memory_note", "mode", "search",
                        "query", query, "items", items), false);
    }

    private int score(Map<String, Object> item, String query) {
        if (query.isBlank()) {
            return 1;
        }
        String source = (item.getOrDefault("text", "") + " " + item.getOrDefault("tags", ""))
                .toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isBlank() && source.contains(token)) {
                score++;
            }
        }
        if (score == 0 && source.contains(query.toLowerCase(Locale.ROOT))) {
            return 1;
        }
        return score;
    }

    private int limit(Object value) {
        try {
            return Math.max(1, Math.min(20, Integer.parseInt(String.valueOf(value))));
        } catch (Exception ignored) {
            return 5;
        }
    }
}
