package com.aiwei.tools.web;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 带 SSRF 防护和正文长度限制的网页抓取执行器。
 */
@Component
public class FetchUrlToolExecutor implements ToolExecutor {

    private static final Pattern TITLE = Pattern.compile(
            "(?is)<title[^>]*>(.*?)</title>");
    private final PublicUrlGuard urlGuard;
    private final SearchProperties properties;
    private final WebClient webClient;

    /**
     * 创建网页抓取执行器。
     *
     * @param urlGuard 公网 URL 校验器
     * @param properties 网页工具配置
     * @param builder HTTP 客户端构建器
     */
    public FetchUrlToolExecutor(
            PublicUrlGuard urlGuard,
            SearchProperties properties,
            WebClient.Builder builder) {
        this.urlGuard = urlGuard;
        this.properties = properties;
        this.webClient = builder.build();
    }

    @Override
    public String toolName() {
        return "mcp.fetch.url";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String rawUrl = String.valueOf(request.arguments().getOrDefault("url", "")).trim();
        if (rawUrl.isBlank()) {
            throw new ToolExecutionException("INVALID_ARGUMENT", "url is required",
                    false, "请提供要读取的网页地址。");
        }
        URI uri = urlGuard.validate(rawUrl);
        try {
            String html = webClient.get().uri(uri)
                    .header(HttpHeaders.USER_AGENT, "aiwei-tools-service/0.1")
                    .accept(MediaType.TEXT_HTML, MediaType.TEXT_PLAIN)
                    .retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofMillis(properties.timeoutMs())).block();
            String source = html == null ? "" : html;
            String title = title(source);
            String text = plainText(source);
            if (text.length() > properties.fetchMaxChars()) {
                text = text.substring(0, properties.fetchMaxChars());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", uri.toString());
            data.put("title", title);
            data.put("text", text);
            data.put("length", text.length());
            return new ToolExecutionResult("builtin_safe_fetch",
                    "已读取" + (title.isBlank() ? "网页" : "“" + title + "”")
                            + "，提取到" + text.length() + "个字符。",
                    data, false);
        } catch (ToolExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new ToolExecutionException("UPSTREAM_FAILED",
                    "Fetch URL failed: " + error.getMessage(), true,
                    "网页暂时无法读取，请稍后再试。");
        }
    }

    private String title(String html) {
        Matcher matcher = TITLE.matcher(html);
        return matcher.find() ? decode(matcher.group(1)).replaceAll("\\s+", " ").trim() : "";
    }

    private String plainText(String html) {
        return decode(html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?is)<[^>]+>", " "))
                .replaceAll("\\s+", " ").trim();
    }

    private String decode(String value) {
        return value.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
