package com.aiwei.tools.web;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用网页搜索执行器。
 */
@Component
public class WebSearchToolExecutor implements ToolExecutor {

    private final WebSearchClient client;

    /**
     * 创建网页搜索执行器。
     *
     * @param client 搜索客户端
     */
    public WebSearchToolExecutor(WebSearchClient client) {
        this.client = client;
    }

    @Override
    public String toolName() {
        return "mcp.search.web";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        return executeSearch(request, false);
    }

    /**
     * 执行搜索，可由新闻执行器复用。
     *
     * @param request 标准请求
     * @param newsMode 新闻模式
     * @return 搜索结果
     */
    public ToolExecutionResult executeSearch(ToolInvokeRequest request, boolean newsMode) {
        String query = first(request, "query", "keyword", "text");
        if (query.isBlank()) {
            throw new ToolExecutionException("INVALID_ARGUMENT", "query is required",
                    false, "请提供要搜索的内容。");
        }
        int limit = integer(request.arguments().get("limit"), 5);
        WebSearchClient.SearchResult result = client.search(query, Math.max(1, Math.min(10, limit)), newsMode);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("answer", result.answer());
        data.put("items", result.items());
        String summary = !result.answer().isBlank()
                ? truncate(result.answer(), 300)
                : result.items().isEmpty() ? "暂时没有找到可靠结果。"
                : String.valueOf(result.items().get(0).getOrDefault("digest",
                result.items().get(0).getOrDefault("title", "已找到相关结果。")));
        return new ToolExecutionResult(result.provider(), summary, data, false);
    }

    private String first(ToolInvokeRequest request, String... keys) {
        for (String key : keys) {
            Object value = request.arguments().get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private int integer(Object value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
