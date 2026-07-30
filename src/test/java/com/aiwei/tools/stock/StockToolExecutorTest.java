package com.aiwei.tools.stock;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 股票实时行情和 K 线执行器测试。
 */
class StockToolExecutorTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/quote", exchange -> respond(exchange, """
                {
                  "error_code":0,
                  "result":[{"data":{
                    "gid":"sh600519",
                    "name":"贵州茅台",
                    "nowPri":"1450.20",
                    "todayStartPri":"1438.00",
                    "todayMax":"1460.00",
                    "todayMin":"1430.00",
                    "yestodEndPri":"1440.00",
                    "increPer":"0.71",
                    "increase":"10.20",
                    "traNumber":"12345"
                  }}]
                }
                """));
        server.createContext("/kline", exchange -> respond(exchange, """
                {
                  "rc":0,
                  "data":{"klines":[
                    "2026-07-28,1400.00,1420.00,1430.00,1390.00,1000",
                    "2026-07-29,1420.00,1450.20,1460.00,1410.00,1200"
                  ]}
                }
                """));
        server.createContext("/tencent", exchange -> {
            if (!"q=hk01810".equals(exchange.getRequestURI().getQuery())) {
                respondText(exchange, "v_unknown=\"\";");
                return;
            }
            respondText(exchange,
                    "v_hk01810=\"100~XIAOMI-W~01810~31.120~31.880~32.400~"
                            + "182544102.0~0~0~31.120~0~0~0~0~0~0~0~0~0~31.120~0~0~0~0~0~0~0~0~0~"
                            + "182544102.0~2026/07/30 14:14:06~-0.760~-2.38~32.400~30.800~31.120~"
                            + "182544102.0~5714747254.872~0\";");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void quoteUsesJuheRealtimeData() {
        StockProperties properties = properties("stock-key");
        StockMarketClient client = client(properties);
        StockQuoteToolExecutor executor = new StockQuoteToolExecutor(new StockSymbolResolver(), client);

        ToolExecutionResult result = executor.execute(request(
                Map.of("symbol", "贵州茅台")));

        assertThat(result.provider()).isEqualTo("juhe_stock");
        assertThat(result.summary()).contains("贵州茅台", "1450.20", "涨 0.71%（上涨 10.20）");
        assertThat(result.data()).containsEntry("symbol", "sh600519");
        assertThat(result.data()).containsEntry("delayed", false);
    }

    @Test
    void hongKongQuoteUsesTencentWithoutWaitingForKline() {
        StockProperties properties = properties("");
        StockMarketClient client = client(properties);
        StockQuoteToolExecutor executor = new StockQuoteToolExecutor(new StockSymbolResolver(), client);

        ToolExecutionResult result = executor.execute(request(Map.of("symbol", "hk01810")));

        assertThat(result.provider()).isEqualTo("tencent_quote");
        assertThat(result.summary()).contains("跌 2.38%（下跌 0.760）");
        assertThat(result.data())
                .containsEntry("symbol", "hk01810")
                .containsEntry("price", "31.120")
                .containsEntry("currency", "HKD")
                .containsEntry("delayed", false);
    }

    @Test
    void quoteFallsBackToLatestMarketBarWithoutJuheKey() {
        StockMarketClient client = client(properties(""));
        StockQuoteToolExecutor executor = new StockQuoteToolExecutor(new StockSymbolResolver(), client);

        ToolExecutionResult result = executor.execute(request(Map.of("symbol", "600519")));

        assertThat(result.provider()).isEqualTo("eastmoney_kline");
        assertThat(result.summary()).contains("最近交易日收盘价", "1450.20");
        assertThat(result.data()).containsEntry("delayed", true);
    }

    @Test
    void klineReturnsOhlcvBars() {
        StockMarketClient client = client(properties(""));
        StockKlineToolExecutor executor = new StockKlineToolExecutor(new StockSymbolResolver(), client);

        ToolExecutionResult result = executor.execute(request(
                Map.of("symbol", "茅台", "period", "30d", "interval", "1d")));

        assertThat(result.provider()).isEqualTo("eastmoney_kline");
        assertThat(result.data()).containsEntry("symbol", "sh600519");
        assertThat((java.util.List<?>) result.data().get("bars")).hasSize(2);
        assertThat(result.data().get("closes"))
                .isEqualTo(java.util.List.of("1420.00", "1450.20"));
    }

    @Test
    void quoteRejectsClearlyMisroutedUrlRequestBeforeCallingProvider() {
        StockMarketClient client = mock(StockMarketClient.class);
        StockQuoteToolExecutor executor = new StockQuoteToolExecutor(new StockSymbolResolver(), client);

        assertThatThrownBy(() -> executor.execute(request(Map.of(
                "symbol", "202306",
                "text", "https://www.gov.cn/yaowen/liebiao/202306/content_6885154.htm帮我抓取下这个网站的内容"))))
                .isInstanceOfSatisfying(ToolExecutionException.class,
                        error -> assertThat(error.code()).isEqualTo("MISROUTED_WEB_REQUEST"));
        verifyNoInteractions(client);
    }

    private StockProperties properties(String key) {
        return new StockProperties(
                key,
                baseUrl + "/quote",
                baseUrl + "/quote",
                baseUrl + "/quote",
                baseUrl + "/tencent?q=",
                baseUrl + "/kline",
                3000,
                300);
    }

    private StockMarketClient client(StockProperties properties) {
        return new StockMarketClient(properties, new ObjectMapper(), WebClient.builder());
    }

    private ToolInvokeRequest request(Map<String, Object> arguments) {
        return new ToolInvokeRequest(
                "req-stock",
                "default",
                "user-1",
                "session-1",
                arguments,
                ToolContext.empty(),
                null);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void respondText(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] response = body.getBytes(java.nio.charset.Charset.forName("GB18030"));
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
