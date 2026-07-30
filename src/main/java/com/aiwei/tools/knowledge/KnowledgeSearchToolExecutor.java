package com.aiwei.tools.knowledge;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 检索随服务发布的 aiweiOS 基础知识；不冒充外部知识库结果。
 */
@Component
public class KnowledgeSearchToolExecutor implements ToolExecutor {

    private static final List<Map<String, Object>> DOCUMENTS = List.of(
            Map.of("id", "architecture", "title", "aiweiOS 架构",
                    "content", "设备侧负责唤醒、采音、播放、基础控制和 OTA；云端负责实时语音、工具编排与任务执行。",
                    "keywords", "架构 mcu 云端 设备 语音 agent"),
            Map.of("id", "task-agent", "title", "实时与异步任务边界",
                    "content", "实时对话链路负责低延时交互，耗时工具由任务链路执行，完成后再主动通知。",
                    "keywords", "任务 异步 实时 延时 工具"),
            Map.of("id", "tools-service", "title", "独立工具服务",
                    "content", "工具能力通过稳定 HTTP 契约独立部署，AINAS 与 aiweios 保留各自普通对话和设备链路。",
                    "keywords", "工具 服务 ainas aiweios http 迁移"));

    @Override
    public String toolName() {
        return "knowledge.search";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String query = String.valueOf(request.arguments().getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            throw new ToolExecutionException("QUERY_REQUIRED", "query is required",
                    false, "请说明要检索的知识主题。");
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matches = DOCUMENTS.stream()
                .filter(document -> matches(document, normalized))
                .toList();
        if (matches.isEmpty()) {
            throw new ToolExecutionException("NO_RESULTS", "no built-in knowledge matched",
                    false, "内置知识中暂时没有找到相关内容。");
        }
        String answer = String.valueOf(matches.get(0).get("content"));
        return new ToolExecutionResult("builtin_aiweios_knowledge", answer,
                Map.of("type", "knowledge_results", "query", query, "items", matches,
                        "scope", "builtin_aiweios_docs"), false);
    }

    private boolean matches(Map<String, Object> document, String query) {
        String searchable = (document.get("title") + " " + document.get("content")
                + " " + document.get("keywords")).toLowerCase(Locale.ROOT);
        if (searchable.contains(query)) {
            return true;
        }
        return query.codePoints().filter(Character::isLetterOrDigit)
                .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
                .filter(token -> searchable.contains(token))
                .count() >= Math.min(2, query.codePointCount(0, query.length()));
    }
}
