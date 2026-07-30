package com.aiwei.tools.web;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.information.NewsSearchToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 网页搜索、新闻搜索、网页抓取和 SSRF 防护测试。
 */
class InformationWebToolTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> respond(exchange, """
                {"AbstractText":"测试主题的可靠摘要","RelatedTopics":[
                  {"Text":"测试标题 - 测试摘要","FirstURL":"https://example.com/news"}
                ]}
                """, "application/json"));
        server.createContext("/page", exchange -> respond(exchange, """
                <html><head><title>测试网页</title><style>x{}</style></head>
                <body><h1>正文标题</h1><script>alert(1)</script><p>这里是正文内容。</p></body></html>
                """, "text/html"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void webAndNewsSearchUseStructuredReferences() {
        SearchProperties properties = properties();
        WebSearchClient client = new WebSearchClient(
                properties, new ObjectMapper(), WebClient.builder());
        WebSearchToolExecutor web = new WebSearchToolExecutor(client);

        ToolExecutionResult webResult = web.execute(request(Map.of("query", "测试主题")));
        ToolExecutionResult newsResult = new NewsSearchToolExecutor(web)
                .execute(request(Map.of("keyword", "测试新闻")));

        assertThat(webResult.provider()).isEqualTo("duckduckgo_instant_answer");
        assertThat(webResult.summary()).contains("可靠摘要");
        assertThat(newsResult.data()).containsKey("items");
    }

    @Test
    void fetchExtractsTextAfterGuardApproval() {
        PublicUrlGuard guard = mock(PublicUrlGuard.class);
        when(guard.validate("https://example.com/page"))
                .thenReturn(URI.create(baseUrl + "/page"));
        FetchUrlToolExecutor executor = new FetchUrlToolExecutor(
                guard, properties(), WebClient.builder());

        ToolExecutionResult result = executor.execute(
                request(Map.of("url", "https://example.com/page")));

        assertThat(result.summary()).contains("测试网页");
        assertThat(result.data().get("text").toString())
                .contains("正文标题", "这里是正文内容")
                .doesNotContain("alert(1)");
    }

    @Test
    void guardBlocksLoopbackAndCloudMetadataAddresses() {
        PublicUrlGuard guard = new PublicUrlGuard();

        assertThatThrownBy(() -> guard.validate("http://127.0.0.1/admin"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("不能访问");
        assertThatThrownBy(() -> guard.validate("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("不能访问");
    }

    private SearchProperties properties() {
        return new SearchProperties(
                "", baseUrl + "/search", "test",
                baseUrl + "/search", 3000, 20000);
    }

    private ToolInvokeRequest request(Map<String, Object> arguments) {
        return new ToolInvokeRequest("req-web", "default", "user", "session",
                arguments, ToolContext.empty(), null);
    }

    private void respond(HttpExchange exchange, String body, String contentType) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
