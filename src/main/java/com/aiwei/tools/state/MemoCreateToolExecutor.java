package com.aiwei.tools.state;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 创建租户和用户隔离的持久化备忘录。
 */
@Component
public class MemoCreateToolExecutor implements ToolExecutor {

    private final TenantStateRepository repository;

    /**
     * 创建备忘录执行器。
     *
     * @param repository 状态仓库
     */
    public MemoCreateToolExecutor(TenantStateRepository repository) {
        this.repository = repository;
    }

    @Override
    public String toolName() {
        return "memo.create";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String text = String.valueOf(request.arguments().getOrDefault("text", "")).trim();
        if (text.isBlank()) {
            throw new ToolExecutionException("TEXT_REQUIRED", "memo text is required",
                    false, "请说明要记录的内容。");
        }
        Object tags = request.arguments().getOrDefault("tags", List.of("memo"));
        if (tags == null) {
            tags = List.of("memo");
        }
        Map<String, Object> item = repository.append("memos", request,
                Map.of("text", text, "tags", tags, "status", "active"));
        return new ToolExecutionResult("tenant_jsonl_store", "备忘录已保存。",
                Map.of("type", "memo", "item", item), false);
    }
}
