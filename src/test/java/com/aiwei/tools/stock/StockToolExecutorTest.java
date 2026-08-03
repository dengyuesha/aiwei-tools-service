/*
 * 2026-08-03 Codex 修改：覆盖跨市场股票带噪口语、代码和美股 ticker 解析。
 */
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
import java.util.concurrent.atomic.AtomicReference;

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
    private final AtomicReference<String> lastKlineQuery = new AtomicReference<>("");

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
        server.createContext("/kline", exchange -> {
            lastKlineQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, """
                {
                  "rc":0,
                  "data":{"klines":[
                    "2026-07-28,1400.00,1420.00,1430.00,1390.00,1000",
                    "2026-07-29,1420.00,1450.20,1460.00,1410.00,1200"
                  ]}
                }
                """);
        });
        server.createContext("/tencent-kline", exchange -> respond(exchange, """
                {"code":0,"data":{"sh600519":{"qfqday":[
                  ["2026-07-28","1400.00","1420.00","1430.00","1390.00","1000"],
                  ["2026-07-29","1420.00","1450.20","1460.00","1410.00","1200"]
                ]}}}
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

    /**
     * 模型把周期尾巴拼进 symbol 时，仍应提取最长明确股票名称。
     */
    @Test
    void klineExtractsHongKongAliasFromNoisyNaturalLanguage() {
        StockMarketClient client = client(properties(""));
        StockKlineToolExecutor executor = new StockKlineToolExecutor(new StockSymbolResolver(), client);

        ToolExecutionResult result = executor.execute(request(Map.of(
                "symbol", "查一下阿里巴巴最近30个日工作交易日的K线图",
                "period", "30d",
                "interval", "1d")));

        assertThat(result.data())
                .containsEntry("symbol", "hk09988")
                .containsEntry("market", "hk");
    }

    /**
     * A股、港股和美股代码即使夹在口语中也必须保持市场归属。
     */
    @Test
    void resolverExtractsCrossMarketCodesAndTickersFromSpeech() {
        StockSymbolResolver resolver = new StockSymbolResolver();

        assertThat(resolver.resolve("看看贵州茅台近一年走势").gid()).isEqualTo("sh600519");
        assertThat(resolver.resolve("查港股9988最近30日K线").gid()).isEqualTo("hk09988");
        assertThat(resolver.resolve("AAPL最近三个月的K线").gid()).isEqualTo("aapl");
        assertThat(resolver.resolve("查一下特斯拉最近12周走势").gid()).isEqualTo("tsla");
        assertThat(resolver.resolve("美股BABA最近30个交易日").gid()).isEqualTo("baba");
    }

    /**
     * 纳斯达克和纽交所代码必须使用各自的东方财富市场编号。
     */
    @Test
    void klineUsesCorrectEastMoneyMarketForUnitedStatesTickers() {
        StockKlineToolExecutor executor = new StockKlineToolExecutor(
                new StockSymbolResolver(), client(properties("")));

        executor.execute(request(Map.of("symbol", "AAPL", "period", "30d")));
        assertThat(lastKlineQuery.get()).contains("secid=105.AAPL");

        executor.execute(request(Map.of("symbol", "BABA", "period", "30d")));
        assertThat(lastKlineQuery.get()).contains("secid=106.BABA");
    }

    /**
     * 只有周期没有股票标的时必须继续报参数错误，不能猜测任意股票。
     */
    @Test
    void resolverStillRejectsPeriodWithoutStockIdentity() {
        StockSymbolResolver resolver = new StockSymbolResolver();

        assertThatThrownBy(() -> resolver.resolve("最近30个交易日K线"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol is required");
    }

    @Test
    void klineFallsBackToTencentWhenEastMoneyIsUnavailable() {
        StockProperties defaults = properties("");
        StockProperties properties = new StockProperties(
                defaults.juheApiKey(), defaults.juheHsUrl(), defaults.juheHkUrl(),
                defaults.juheUsUrl(), defaults.tencentQuoteUrl(), defaults.tencentKlineUrl(),
                baseUrl + "/missing-kline", defaults.timeoutMs(), defaults.maxBars());
        StockKlineToolExecutor executor = new StockKlineToolExecutor(
                new StockSymbolResolver(), client(properties));

        ToolExecutionResult result = executor.execute(request(
                Map.of("symbol", "贵州茅台", "period", "30d")));

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
                baseUrl + "/tencent-kline",
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
