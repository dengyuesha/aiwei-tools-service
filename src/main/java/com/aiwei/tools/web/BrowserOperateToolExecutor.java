package com.aiwei.tools.web;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全浏览器占位执行器：只做搜索和 SSRF 防护后的只读网页提取。
 */
@Component
public class BrowserOperateToolExecutor implements ToolExecutor {

    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+",
            Pattern.CASE_INSENSITIVE);
    private final FetchUrlToolExecutor fetchExecutor;
    private final WebSearchClient searchClient;

    /**
     * 创建浏览器占位执行器。
     *
     * @param fetchExecutor 安全网页抓取器
     * @param searchClient 网页搜索客户端
     */
    public BrowserOperateToolExecutor(
            FetchUrlToolExecutor fetchExecutor,
            WebSearchClient searchClient) {
        this.fetchExecutor = fetchExecutor;
        this.searchClient = searchClient;
    }

    @Override
    public String toolName() {
        return "mcp.browser.operate";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String url = value(request, "url");
        if (url.isBlank()) {
            Matcher matcher = URL.matcher(value(request, "text"));
            if (matcher.find()) {
                url = matcher.group();
            }
        }
        if (url.isBlank()) {
            String query = value(request, "query");
            if (query.isBlank()) {
                query = value(request, "text");
            }
            WebSearchClient.SearchResult search = searchClient.search(query, 5, false);
            url = search.items().stream().map(item -> String.valueOf(item.getOrDefault("url", "")))
                    .filter(candidate -> !candidate.isBlank()).findFirst().orElse("");
        }
        if (url.isBlank()) {
            throw new ToolExecutionException("URL_REQUIRED", "no browsable URL found",
                    false, "没有找到可以读取的网页地址。");
        }
        Map<String, Object> arguments = new LinkedHashMap<>(request.arguments());
        arguments.put("url", url);
        ToolInvokeRequest fetchRequest = new ToolInvokeRequest(
                request.requestId(), request.tenantId(), request.userId(), request.sessionId(),
                arguments, request.context(), request.idempotencyKey());
        ToolExecutionResult fetched = fetchExecutor.execute(fetchRequest);
        Map<String, Object> data = new LinkedHashMap<>(fetched.data());
        data.put("type", "browser_task");
        data.put("status", "done");
        data.put("mode", "safe_http_read_only");
        data.put("interactive_browser", false);
        return new ToolExecutionResult("builtin_browser_safe_stub",
                "已用只读模式完成网页内容提取。", data, false);
    }

    private String value(ToolInvokeRequest request, String key) {
        Object value = request.arguments().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
