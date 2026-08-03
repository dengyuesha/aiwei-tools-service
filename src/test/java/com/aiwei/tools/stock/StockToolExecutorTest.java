/*
 * 2026-08-03 Codex 修改：覆盖动态证券搜索、跨市场股价/K线、带噪口语和美股 ticker 解析。
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
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AtomicReference<String> lastNasdaqQuery = new AtomicReference<>("");
    private final AtomicInteger nasdaqAaplRequests = new AtomicInteger();
    private final AtomicReference<String> lastSuggestQuery = new AtomicReference<>("");
    private final AtomicInteger suggestRequests = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/quote", exchange -> {
            String query = String.valueOf(exchange.getRequestURI().getRawQuery());
            String gid = query.contains("gid=mu") ? "mu" : "sh600519";
            String name = "mu".equals(gid) ? "美光科技" : "贵州茅台";
            respond(exchange, """
                {
                  "error_code":0,
                  "result":[{"data":{
                    "gid":"%s",
                    "name":"%s",
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
                """.formatted(gid, name));
        });
        server.createContext("/kline", exchange -> {
            lastKlineQuery.set(exchange.getRequestURI().getRawQuery());
            if (lastKlineQuery.get().matches(".*secid=10[56]\\..*")) {
                respond(exchange, "{\"rc\":-1,\"data\":null}");
                return;
            }
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
        server.createContext("/nasdaq/", exchange -> {
            lastNasdaqQuery.set(exchange.getRequestURI().getRawQuery());
            if (exchange.getRequestURI().getPath().contains("/AAPL/")
                    && nasdaqAaplRequests.incrementAndGet() == 1) {
                respondStatus(exchange, 503, "{\"message\":\"temporary unavailable\"}");
                return;
            }
            respond(exchange, """
                    {"data":{"tradesTable":{"rows":[
                      {"date":"07/31/2026","close":"$308.91","volume":"132,489,100","open":"$304.81","high":"$310.69","low":"$300.00"},
                      {"date":"07/30/2026","close":"$333.43","volume":"74,817,790","open":"$333.10","high":"$334.75","low":"$329.59"}
                    ]}}}
                    """);
        });
        server.createContext("/tencent-kline", exchange -> respond(exchange, """
                {"code":0,"data":{"sh600519":{"qfqday":[
                  ["2026-07-28","1400.00","1420.00","1430.00","1390.00","1000"],
                  ["2026-07-29","1420.00","1450.20","1460.00","1410.00","1200"]
                ]}}}
                """));
        server.createContext("/tencent", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (!java.util.Set.of("q=hk01810", "q=hk00700").contains(query)) {
                respondText(exchange, "v_unknown=\"\";");
                return;
            }
            String code = query.substring("q=".length());
            respondText(exchange,
                    "v_" + code + "=\"100~TENCENT~" + code.substring(2)
                            + "~31.120~31.880~32.400~"
                            + "182544102.0~0~0~31.120~0~0~0~0~0~0~0~0~0~31.120~0~0~0~0~0~0~0~0~0~"
                            + "182544102.0~2026/07/30 14:14:06~-0.760~-2.38~32.400~30.800~31.120~"
                            + "182544102.0~5714747254.872~0\";");
        });
        server.createContext("/suggest", exchange -> {
            lastSuggestQuery.set(exchange.getRequestURI().getRawQuery());
            suggestRequests.incrementAndGet();
            respondText(exchange, "v_hint=\"sh~600519~贵州茅台~gzmt~GP-A"
                        + "^hk~00700~腾讯控股~txkg~GP"
                        + "^us~mu.oq~美光科技~mgkj~GP"
                        + "^us~aapl.oq~苹果~pg~GP"
                        + "^us~tsla.oq~特斯拉~tsl~GP"
                        + "^hk~09988~阿里巴巴-W~albbw~GP"
                        + "^us~baba.n~阿里巴巴~albb~GP"
                        + "^sz~300750~宁德时代~ndsd~GP-A"
                        + "^hk~03750~宁德时代~ndsd~GP\";");
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
        StockQuoteToolExecutor executor = new StockQuoteToolExecutor(resolver(), client);

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
        StockQuoteToolExecutor executor = new StockQuoteToolExecutor(resolver(), client);

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
        StockQuoteToolExecutor executor = new StockQuoteToolExecutor(resolver(), client);

        ToolExecutionResult result = executor.execute(request(Map.of("symbol", "600519")));

        assertThat(result.provider()).isEqualTo("eastmoney_kline");
        assertThat(result.summary()).contains("最近交易日收盘价", "1450.20");
        assertThat(result.data()).containsEntry("delayed", true);
    }

    @Test
    void klineReturnsOhlcvBars() {
        StockMarketClient client = client(properties(""));
        StockKlineToolExecutor executor = new StockKlineToolExecutor(resolver(), client);

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
        StockKlineToolExecutor executor = new StockKlineToolExecutor(resolver(), client);

        ToolExecutionResult result = executor.execute(request(Map.of(
                "symbol", "查一下港股阿里巴巴最近30个日工作交易日的K线图",
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
        StockSymbolResolver resolver = resolver();

        assertThat(resolver.resolve("看看贵州茅台近一年走势").gid()).isEqualTo("sh600519");
        assertThat(resolver.resolve("查港股9988最近30日K线").gid()).isEqualTo("hk09988");
        assertThat(resolver.resolve("AAPL最近三个月的K线").gid()).isEqualTo("aapl");
        assertThat(resolver.resolve("查一下特斯拉最近12周走势").gid()).isEqualTo("tsla");
        assertThat(lastSuggestQuery.get()).contains("q=%E7%89%B9%E6%96%AF%E6%8B%89");
        assertThat(resolver.resolve("美股BABA最近30个交易日").gid()).isEqualTo("baba");
    }

    /**
     * 同一公司和市场的重复请求必须命中本地缓存，避免每次行情都额外搜索证券代码。
     */
    @Test
    void resolverCachesDynamicSymbolResults() {
        StockSymbolResolver resolver = resolver();

        resolver.resolve("查美股美光科技股价");
        resolver.resolve("查询美股美光科技最近30个交易日K线");

        assertThat(suggestRequests.get()).isEqualTo(1);
    }

    /**
     * 中文公司名称必须通过证券搜索动态解析，不能依赖业务代码里的固定公司名单。
     */
    @Test
    void resolverSearchesChineseNamesAcrossMainlandHongKongAndUnitedStatesMarkets() {
        StockSymbolResolver resolver = resolver();

        assertThat(resolver.resolve("查A股贵州茅台股价"))
                .extracting(StockSymbolResolver.StockTarget::market,
                        StockSymbolResolver.StockTarget::gid,
                        StockSymbolResolver.StockTarget::displayName)
                .containsExactly("hs", "sh600519", "贵州茅台");
        assertThat(resolver.resolve("查港股腾讯控股股价"))
                .extracting(StockSymbolResolver.StockTarget::market,
                        StockSymbolResolver.StockTarget::gid,
                        StockSymbolResolver.StockTarget::displayName)
                .containsExactly("hk", "hk00700", "腾讯控股");
        assertThat(resolver.resolve("查美股美光科技股价"))
                .extracting(StockSymbolResolver.StockTarget::market,
                        StockSymbolResolver.StockTarget::gid,
                        StockSymbolResolver.StockTarget::displayName)
                .containsExactly("us", "mu", "美光科技");
        assertThat(resolver.resolve("查阿里巴巴最近30个交易日K线").gid())
                .isEqualTo("hk09988");
    }

    /**
     * 三个市场的中文名称都必须完成真实执行器链路，并保留生成式 UI 需要的标准字段。
     */
    @Test
    void quoteAndKlineAcceptDynamicNamesForAllSupportedMarkets() {
        StockQuoteToolExecutor quote = new StockQuoteToolExecutor(resolver(), client(properties("stock-key")));
        StockKlineToolExecutor kline = new StockKlineToolExecutor(resolver(), client(properties("")));

        ToolExecutionResult mainlandQuote = quote.execute(request(Map.of("symbol", "A股贵州茅台")));
        ToolExecutionResult hongKongQuote = quote.execute(request(Map.of("symbol", "港股腾讯控股")));
        ToolExecutionResult unitedStatesQuote = quote.execute(request(Map.of("symbol", "美股美光科技")));
        ToolExecutionResult mainlandKline = kline.execute(request(Map.of(
                "symbol", "A股贵州茅台", "period", "30d", "interval", "1d")));
        ToolExecutionResult hongKongKline = kline.execute(request(Map.of(
                "symbol", "港股腾讯控股", "period", "30d", "interval", "1d")));
        ToolExecutionResult unitedStatesKline = kline.execute(request(Map.of(
                "symbol", "美股美光科技", "period", "30d", "interval", "1d")));

        assertThat(mainlandQuote.data()).containsEntry("market", "hs").containsEntry("symbol", "sh600519");
        assertThat(hongKongQuote.data()).containsEntry("market", "hk").containsEntry("symbol", "hk00700");
        assertThat(unitedStatesQuote.data()).containsEntry("market", "us").containsEntry("symbol", "mu");
        assertThat(mainlandKline.data()).containsEntry("market", "hs").containsEntry("symbol", "sh600519");
        assertThat(hongKongKline.data()).containsEntry("market", "hk").containsEntry("symbol", "hk00700");
        assertThat(unitedStatesKline.data()).containsEntry("market", "us").containsEntry("symbol", "mu");
        for (ToolExecutionResult result : java.util.List.of(
                mainlandKline, hongKongKline, unitedStatesKline)) {
            assertThat((java.util.List<?>) result.data().get("bars")).hasSize(2);
            assertThat(result.data()).containsKeys("name", "period", "interval", "closes");
        }
    }

    /**
     * 纳斯达克和纽交所代码必须使用各自的东方财富市场编号。
     */
    @Test
    void klineUsesNasdaqHistoryForUnitedStatesTickers() {
        StockKlineToolExecutor executor = new StockKlineToolExecutor(
                resolver(), client(properties("")));

        executor.execute(request(Map.of("symbol", "AAPL", "period", "30d")));
        assertThat(nasdaqAaplRequests.get()).isEqualTo(2);
        assertThat(lastNasdaqQuery.get())
                .contains("assetclass=stocks", "limit=30");

        executor.execute(request(Map.of("symbol", "BABA", "period", "30d")));
        assertThat(lastNasdaqQuery.get())
                .contains("assetclass=stocks", "limit=30");
    }

    /**
     * 只有周期没有股票标的时必须继续报参数错误，不能猜测任意股票。
     */
    @Test
    void resolverStillRejectsPeriodWithoutStockIdentity() {
        StockSymbolResolver resolver = resolver();

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
                baseUrl + "/missing-kline", defaults.nasdaqHistoricalUrl(),
                defaults.timeoutMs(), defaults.maxBars());
        StockKlineToolExecutor executor = new StockKlineToolExecutor(
                resolver(), client(properties));

        ToolExecutionResult result = executor.execute(request(
                Map.of("symbol", "贵州茅台", "period", "30d")));

        assertThat((java.util.List<?>) result.data().get("bars")).hasSize(2);
        assertThat(result.data().get("closes"))
                .isEqualTo(java.util.List.of("1420.00", "1450.20"));
    }

    @Test
    void quoteRejectsClearlyMisroutedUrlRequestBeforeCallingProvider() {
        StockMarketClient client = mock(StockMarketClient.class);
        StockQuoteToolExecutor executor = new StockQuoteToolExecutor(resolver(), client);

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
                baseUrl + "/nasdaq/{symbol}/historical",
                3000,
                300);
    }

    private StockMarketClient client(StockProperties properties) {
        return new StockMarketClient(properties, new ObjectMapper(), WebClient.builder());
    }

    private StockSymbolResolver resolver() {
        StockSecuritySearchClient searchClient = new StockSecuritySearchClient(
                WebClient.builder(), new ObjectMapper(), baseUrl + "/suggest", 1000);
        return new StockSymbolResolver(searchClient);
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
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=GB18030");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void respondStatus(
            com.sun.net.httpserver.HttpExchange exchange,
            int status,
            String body) throws java.io.IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
