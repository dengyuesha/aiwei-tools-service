package com.aiwei.tools.state;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 写入租户和用户隔离的基础记忆。
 */
@Component
public class MemoryWriteToolExecutor implements ToolExecutor {

    private final TenantStateRepository repository;

    /**
     * 创建记忆写入执行器。
     *
     * @param repository 状态仓库
     */
    public MemoryWriteToolExecutor(TenantStateRepository repository) {
        this.repository = repository;
    }

    @Override
    public String toolName() {
        return "mcp.memory.write";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String text = String.valueOf(request.arguments().getOrDefault("text",
                request.arguments().getOrDefault("memory", ""))).trim();
        if (text.isBlank()) {
            throw new ToolExecutionException("TEXT_REQUIRED", "memory text is required",
                    false, "请说明要记住的内容。");
        }
        Object tags = request.arguments().getOrDefault("tags", List.of("memory"));
        Object source = request.arguments().getOrDefault("source", "tools_service");
        Map<String, Object> item = repository.append("memories", request, Map.of(
                "text", text,
                "tags", tags == null ? List.of("memory") : tags,
                "source", source == null ? "tools_service" : String.valueOf(source)));
        return new ToolExecutionResult("tenant_jsonl_memory", "记忆已保存。",
                Map.of("type", "memory_note", "mode", "write", "item", item), false);
    }
}
