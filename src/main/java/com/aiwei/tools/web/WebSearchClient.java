package com.aiwei.tools.web;

import com.aiwei.tools.execution.ToolExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百度 AI 搜索与 DuckDuckGo 降级客户端。
 */
@Component
public class WebSearchClient {

    private final SearchProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    /**
     * 创建搜索客户端。
     *
     * @param properties 搜索配置
     * @param objectMapper JSON 解析器
     * @param builder HTTP 客户端构建器
     */
    public WebSearchClient(SearchProperties properties, ObjectMapper objectMapper, WebClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = builder.build();
    }

    /**
     * 搜索网页并返回结构化引用。
     *
     * @param query 查询文本
     * @param limit 最大结果数量
     * @param newsMode 是否使用新闻摘要提示
     * @return 搜索结果
     */
    public SearchResult search(String query, int limit, boolean newsMode) {
        if (!properties.baiduApiKey().isBlank()) {
            try {
                SearchResult result = baidu(query, limit, newsMode);
                if (!result.items().isEmpty() || !result.answer().isBlank()) {
                    return result;
                }
            } catch (RuntimeException ignored) {
                // 百度搜索异常时继续尝试无密钥的即时答案源。
            }
        }
        return duckduckgo(query, limit);
    }

    private SearchResult baidu(String query, int limit, boolean newsMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        String prompt = newsMode
                ? "请检索并用中文概括最近的相关新闻，区分事实与传闻：" + query
                : "请用中文简要回答并提供可核实网页引用：" + query;
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("stream", false);
        body.put("search_source", properties.baiduSource());
        body.put("resource_type_filter", List.of(Map.of("type", "web", "top_k", Math.max(4, limit))));
        body.put("enable_deep_search", false);
        body.put("temperature", 0.1);
        try {
            String response = webClient.post().uri(properties.baiduEndpoint())
                    .headers(headers -> {
                        headers.setBearerAuth(properties.baiduApiKey());
                        headers.set("X-Appbuilder-Authorization", "Bearer " + properties.baiduApiKey());
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body).retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofMillis(properties.timeoutMs())).block();
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode reference : root.path("references")) {
                items.add(reference(reference));
                if (items.size() >= limit) {
                    break;
                }
            }
            String answer = root.path("choices").path(0).path("message").path("content")
                    .asText(root.path("answer").asText(""));
            return new SearchResult("baidu_ai_search", answer, items);
        } catch (Exception error) {
            throw failed(error);
        }
    }

    private SearchResult duckduckgo(String query, int limit) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(properties.duckduckgoEndpoint())
                    .queryParam("q", query).queryParam("format", "json")
                    .queryParam("no_html", 1).queryParam("skip_disambig", 1)
                    .build().encode().toUri();
            String response = webClient.get().uri(uri).retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofMillis(properties.timeoutMs())).block();
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode topic : root.path("RelatedTopics")) {
                if (topic.has("Topics")) {
                    for (JsonNode nested : topic.path("Topics")) {
                        items.add(duckReference(nested));
                    }
                } else {
                    items.add(duckReference(topic));
                }
                if (items.size() >= limit) {
                    break;
                }
            }
            String answer = root.path("AbstractText").asText("");
            if (items.isEmpty() && answer.isBlank()) {
                throw new ToolExecutionException("NO_RESULTS", "Search returned no results",
                        false, "暂时没有找到可靠的搜索结果。");
            }
            return new SearchResult("duckduckgo_instant_answer", answer,
                    items.stream().limit(limit).toList());
        } catch (ToolExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw failed(error);
        }
    }

    private Map<String, Object> reference(JsonNode node) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", node.path("title").asText("搜索结果"));
        item.put("url", node.path("url").asText(""));
        item.put("source", node.path("source").asText(node.path("website").asText("百度 AI 搜索")));
        item.put("digest", node.path("content").asText(node.path("summary").asText("")));
        item.put("published_at", node.path("date").asText(""));
        return item;
    }

    private Map<String, Object> duckReference(JsonNode node) {
        String text = node.path("Text").asText("");
        return Map.of(
                "title", text.contains(" - ") ? text.substring(0, text.indexOf(" - ")) : "搜索结果",
                "url", node.path("FirstURL").asText(""),
                "source", "DuckDuckGo",
                "digest", text);
    }

    private ToolExecutionException failed(Exception error) {
        return new ToolExecutionException("UPSTREAM_FAILED",
                "Web search failed: " + error.getMessage(), true,
                "联网搜索暂时不可用，请稍后再试。");
    }

    /**
     * 中立搜索结果。
     *
     * @param provider 供应商
     * @param answer 供应商生成的摘要
     * @param items 网页引用
     */
    public record SearchResult(String provider, String answer, List<Map<String, Object>> items) {
    }
}
