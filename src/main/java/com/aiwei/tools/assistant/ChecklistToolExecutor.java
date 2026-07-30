package com.aiwei.tools.assistant;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将自然语言任务拆成中立清单，不依赖会话或 UI 卡片。
 */
@Component
public class ChecklistToolExecutor implements ToolExecutor {

    @Override
    public String toolName() {
        return "task.checklist.create";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        Map<String, Object> args = request.arguments();
        String topic = text(args.getOrDefault("topic", "任务清单"));
        String source = text(args.getOrDefault("text", ""));
        List<String> requested = stringList(args.get("items"));
        if (requested.isEmpty() && !source.isBlank()) {
            requested = List.of(source.split("[，,；;、\\n]+")).stream()
                    .map(String::trim).filter(item -> !item.isBlank()).toList();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        if (requested.isEmpty()) {
            requested = List.of("明确目标和交付物", "拆分最小可验证动作", "安排执行时间并检查结果");
        }
        for (int index = 0; index < requested.size(); index++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("text", requested.get(index));
            item.put("priority", index == 0 ? "high" : "medium");
            item.put("done", false);
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "task_checklist");
        data.put("title", topic.isBlank() ? "任务清单" : topic);
        data.put("progress", Map.of("done", 0, "total", items.size()));
        data.put("groups", List.of(Map.of("title", "待执行", "items", items)));
        return new ToolExecutionResult("builtin_checklist",
                "清单已整理，共 " + items.size() + " 项。", data, false);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::text).filter(item -> !item.isBlank()).toList();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
