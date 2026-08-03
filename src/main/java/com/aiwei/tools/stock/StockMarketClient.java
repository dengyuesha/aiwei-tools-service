/*
 * 2026-08-03 Codex 修改：按美股交易所生成东方财富市场编号，兼容纳斯达克与纽交所 K 线。
 */
package com.aiwei.tools.stock;

import com.aiwei.tools.execution.ToolExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 聚合实时行情和东方财富 K 线客户端。
 */
@Component
public class StockMarketClient {

    private static final long FAST_QUOTE_TIMEOUT_MS = 2000L;
    private static final Set<String> NYSE_TICKERS = Set.of("BABA");
    private static final Set<String> AMEX_TICKERS = Set.of();

    private final StockProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    /**
     * 创建股票市场客户端。
     *
     * @param properties 股票供应商配置
     * @param objectMapper JSON 解析器
     * @param webClientBuilder HTTP 客户端构建器
     */
    public StockMarketClient(
            StockProperties properties,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.build();
    }

    /**
     * 查询聚合实时行情。
     *
     * @param target 股票标的
     * @return 标准行情字段
     */
    public Map<String, Object> fetchQuote(StockSymbolResolver.StockTarget target) {
        if (properties.juheApiKey().isBlank()) {
            throw new ToolExecutionException(
                    "PROVIDER_NOT_CONFIGURED",
                    "JUHE_STOCK_KEY is not configured",
                    false,
                    "实时行情密钥未配置。");
        }
        URI uri = switch (target.market()) {
            case "hk" -> UriComponentsBuilder.fromUriString(properties.juheHkUrl())
                    .queryParam("key", properties.juheApiKey())
                    .queryParam("num", target.gid().substring(2))
                    .build().encode().toUri();
            case "us" -> UriComponentsBuilder.fromUriString(properties.juheUsUrl())
                    .queryParam("key", properties.juheApiKey())
                    .queryParam("gid", target.gid())
                    .build().encode().toUri();
            default -> UriComponentsBuilder.fromUriString(properties.juheHsUrl())
                    .queryParam("key", properties.juheApiKey())
                    .queryParam("gid", target.gid())
                    .build().encode().toUri();
        };
        try {
            JsonNode root = getJson(uri);
            validateJuhe(root);
            JsonNode data = extractQuoteData(root);
            if (data == null || data.isEmpty()) {
                throw new IllegalStateException("quote response contains no data");
            }
            Map<String, Object> quote = new LinkedHashMap<>();
            quote.put("symbol", textOr(data, target.gid(), "gid", "Gid"));
            quote.put("name", textOr(data, target.displayName(), "name", "Name"));
            quote.put("price", textOr(data, "--", "nowPri", "Lastestpri", "lastestpri", "price"));
            quote.put("open", text(data, "todayStartPri", "Openpri", "openpri"));
            quote.put("high", text(data, "todayMax", "Maxpri", "maxpri"));
            quote.put("low", text(data, "todayMin", "Minpri", "minpri"));
            quote.put("prev_close", text(data, "yestodEndPri", "Formpri", "formpri"));
            quote.put("change_percent", text(data, "increPer", "Limit", "limit"));
            quote.put("change_amount", text(data, "increase", "Uppic", "uppic"));
            quote.put("volume", text(data, "traNumber", "TraNumber", "volume"));
            quote.put("amount", text(data, "traAmount", "TraAmount", "amount"));
            quote.put("currency", currency(target.market()));
            quote.put("market", target.market());
            return quote;
        } catch (ToolExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new ToolExecutionException(
                    "UPSTREAM_FAILED",
                    "Juhe stock quote failed: " + safeMessage(error),
                    true,
                    "实时行情暂时查不到。");
        }
    }

    /**
     * Fetches a lightweight realtime quote without waiting for K-line data.
     *
     * @param target resolved A-share or Hong Kong stock
     * @return normalized realtime quote fields
     */
    public Map<String, Object> fetchTencentQuote(StockSymbolResolver.StockTarget target) {
        if (!List.of("hs", "hk").contains(target.market())) {
            throw new ToolExecutionException(
                    "PROVIDER_NOT_SUPPORTED",
                    "Tencent fast quote does not support market " + target.market(),
                    false,
                    "\u5F53\u524D\u5E02\u573A\u6682\u4E0D\u652F\u6301\u817E\u8BAF\u5FEB\u901F\u884C\u60C5\u3002");
        }
        URI uri = URI.create(properties.tencentQuoteUrl() + target.gid().toLowerCase(Locale.ROOT));
        try {
            byte[] bytes = webClient.get()
                    .uri(uri)
                    .accept(MediaType.TEXT_PLAIN, MediaType.ALL)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://gu.qq.com/")
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofMillis(Math.min(properties.timeoutMs(), FAST_QUOTE_TIMEOUT_MS)))
                    .block();
            String body = new String(
                    bytes == null ? new byte[0] : bytes,
                    java.nio.charset.Charset.forName("GB18030"));
            return parseTencentQuote(body, target);
        } catch (ToolExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new ToolExecutionException(
                    "UPSTREAM_FAILED",
                    "Tencent stock quote failed: " + safeMessage(error),
                    true,
                    "\u5B9E\u65F6\u884C\u60C5\u6682\u65F6\u67E5\u4E0D\u5230\u3002");
        }
    }

    static Map<String, Object> parseTencentQuote(
            String response,
            StockSymbolResolver.StockTarget target) {
        String text = response == null ? "" : response.trim();
        int start = text.indexOf('"');
        int end = text.lastIndexOf('"');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Tencent quote returned an invalid response");
        }
        String[] fields = text.substring(start + 1, end).split("~", -1);
        if (fields.length < 39 || fields[3].isBlank() || "--".equals(fields[3])) {
            throw new IllegalArgumentException("Tencent quote did not contain a current price");
        }
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("symbol", target.gid());
        quote.put("name", fields[1].isBlank() ? target.displayName() : fields[1]);
        quote.put("price", fields[3]);
        quote.put("prev_close", fields[4]);
        quote.put("open", fields[5]);
        quote.put("volume", fields[6]);
        quote.put("as_of", fields[30]);
        quote.put("change_amount", fields[31]);
        quote.put("change_percent", fields[32]);
        quote.put("high", fields[33]);
        quote.put("low", fields[34]);
        quote.put("amount", fields[37]);
        quote.put("currency", currencyFor(target.market()));
        quote.put("market", target.market());
        return quote;
    }

    /**
     * 查询日 K 线。
     *
     * @param target 股票标的
     * @param limit 最大交易日数量
     * @return 按日期升序排列的 OHLCV 列表
     */
    public List<Map<String, Object>> fetchDailyBars(
            StockSymbolResolver.StockTarget target,
            int limit) {
        if ("us".equals(target.market())) {
            try {
                return fetchNasdaqDailyBars(target, limit);
            } catch (Exception nasdaqError) {
                try {
                    return fetchEastMoneyDailyBars(target, limit);
                } catch (Exception eastMoneyError) {
                    throw new ToolExecutionException(
                            "UPSTREAM_FAILED",
                            "Kline providers failed: " + safeMessage(nasdaqError)
                                    + "; " + safeMessage(eastMoneyError),
                            true,
                            "股票走势暂时查不到，请稍后再试。");
                }
            }
        }
        try {
            return fetchEastMoneyDailyBars(target, limit);
        } catch (ToolExecutionException eastMoneyError) {
            try {
                return fetchTencentDailyBars(target, limit);
            } catch (Exception tencentError) {
                throw new ToolExecutionException(
                        "UPSTREAM_FAILED",
                        "Kline providers failed: " + safeMessage(eastMoneyError)
                                + "; " + safeMessage(tencentError),
                        true,
                        "股票走势暂时查不到，请稍后再试。");
            }
        }
    }

    private List<Map<String, Object>> fetchNasdaqDailyBars(
            StockSymbolResolver.StockTarget target,
            int limit) throws Exception {
        Exception firstFailure;
        try {
            return fetchNasdaqDailyBarsOnce(target, limit);
        } catch (Exception error) {
            firstFailure = error;
        }
        try {
            Thread.sleep(250L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw firstFailure;
        }
        return fetchNasdaqDailyBarsOnce(target, limit);
    }

    private List<Map<String, Object>> fetchNasdaqDailyBarsOnce(
            StockSymbolResolver.StockTarget target,
            int limit) throws Exception {
        int capped = Math.max(10, Math.min(properties.maxBars(), limit));
        LocalDate today = LocalDate.now();
        URI uri = UriComponentsBuilder.fromUriString(properties.nasdaqHistoricalUrl())
                .buildAndExpand(Map.of("symbol", target.gid().toUpperCase(Locale.ROOT)))
                .toUri();
        uri = UriComponentsBuilder.fromUri(uri)
                .queryParam("assetclass", "stocks")
                .queryParam("fromdate", today.minusDays(Math.max(capped * 2L, 90L)))
                .queryParam("todate", today)
                .queryParam("limit", capped)
                .build().encode().toUri();
        JsonNode root = getNasdaqJson(uri);
        JsonNode rows = root.path("data").path("tradesTable").path("rows");
        List<Map<String, Object>> bars = new ArrayList<>();
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                String date = nasdaqDate(row.path("date").asText(""));
                String open = marketNumber(row.path("open").asText(""));
                String close = marketNumber(row.path("close").asText(""));
                String high = marketNumber(row.path("high").asText(""));
                String low = marketNumber(row.path("low").asText(""));
                if (date.isBlank() || open.isBlank() || close.isBlank()
                        || high.isBlank() || low.isBlank()) {
                    continue;
                }
                Map<String, Object> bar = new LinkedHashMap<>();
                bar.put("date", date);
                bar.put("open", open);
                bar.put("close", close);
                bar.put("high", high);
                bar.put("low", low);
                String volume = marketNumber(row.path("volume").asText(""));
                if (!volume.isBlank()) {
                    bar.put("volume", volume);
                }
                bars.add(bar);
            }
        }
        if (bars.isEmpty()) {
            throw new IllegalStateException("Nasdaq historical response contains no bars");
        }
        java.util.Collections.reverse(bars);
        return bars.size() > limit
                ? new ArrayList<>(bars.subList(bars.size() - limit, bars.size()))
                : bars;
    }

    private String nasdaqDate(String value) {
        try {
            return LocalDate.parse(value, DateTimeFormatter.ofPattern("MM/dd/yyyy"))
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            return "";
        }
    }

    private String marketNumber(String value) {
        return value == null ? "" : value.replace("$", "").replace(",", "").trim();
    }

    private List<Map<String, Object>> fetchEastMoneyDailyBars(
            StockSymbolResolver.StockTarget target,
            int limit) {
        String secId = secId(target);
        int capped = Math.max(10, Math.min(properties.maxBars(), limit));
        String begin = LocalDate.now().minusDays(Math.max(capped * 2L, 90L))
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        URI uri = UriComponentsBuilder.fromUriString(properties.eastMoneyKlineUrl())
                .queryParam("secid", secId)
                .queryParam("ut", "fa5fd1943c7b386f172d6893dbfba10b")
                .queryParam("fields1", "f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13")
                .queryParam("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61")
                .queryParam("klt", "101")
                .queryParam("fqt", "1")
                .queryParam("beg", begin)
                .queryParam("end", "20500101")
                .queryParam("lmt", capped)
                .build()
                .encode()
                .toUri();
        try {
            JsonNode root = getJson(uri);
            if (root.path("rc").asInt(-1) != 0) {
                throw new IllegalStateException("eastmoney rc=" + root.path("rc").asInt());
            }
            JsonNode klines = root.path("data").path("klines");
            List<Map<String, Object>> bars = new ArrayList<>();
            if (klines.isArray()) {
                for (JsonNode line : klines) {
                    String[] fields = line.asText("").split(",");
                    if (fields.length < 5) {
                        continue;
                    }
                    Map<String, Object> bar = new LinkedHashMap<>();
                    bar.put("date", fields[0]);
                    bar.put("open", fields[1]);
                    bar.put("close", fields[2]);
                    bar.put("high", fields[3]);
                    bar.put("low", fields[4]);
                    if (fields.length > 5) {
                        bar.put("volume", fields[5]);
                    }
                    bars.add(bar);
                }
            }
            if (bars.size() > limit) {
                return new ArrayList<>(bars.subList(bars.size() - limit, bars.size()));
            }
            return bars;
        } catch (Exception error) {
            throw new ToolExecutionException(
                    "UPSTREAM_FAILED",
                    "EastMoney kline failed: " + safeMessage(error),
                    true,
                    "股票走势暂时查不到，请稍后再试。");
        }
    }

    private List<Map<String, Object>> fetchTencentDailyBars(
            StockSymbolResolver.StockTarget target,
            int limit) throws Exception {
        int capped = Math.max(10, Math.min(properties.maxBars(), limit));
        String gid = target.gid().toLowerCase(Locale.ROOT);
        URI uri = UriComponentsBuilder.fromUriString(properties.tencentKlineUrl())
                .queryParam("param", gid + ",day,,," + capped + ",qfq")
                .build().encode().toUri();
        JsonNode root = getJson(uri);
        JsonNode stock = root.path("data").path(gid);
        JsonNode lines = stock.has("qfqday") ? stock.path("qfqday") : stock.path("day");
        List<Map<String, Object>> bars = new ArrayList<>();
        if (lines.isArray()) {
            for (JsonNode line : lines) {
                if (!line.isArray() || line.size() < 5) {
                    continue;
                }
                Map<String, Object> bar = new LinkedHashMap<>();
                bar.put("date", line.path(0).asText());
                bar.put("open", line.path(1).asText());
                bar.put("close", line.path(2).asText());
                bar.put("high", line.path(3).asText());
                bar.put("low", line.path(4).asText());
                if (line.size() > 5) {
                    bar.put("volume", line.path(5).asText());
                }
                bars.add(bar);
            }
        }
        if (bars.isEmpty()) {
            throw new IllegalStateException("Tencent kline response contains no bars");
        }
        return bars.size() > limit
                ? new ArrayList<>(bars.subList(bars.size() - limit, bars.size()))
                : bars;
    }

    private JsonNode getJson(URI uri) throws Exception {
        String body = webClient.get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://quote.eastmoney.com/")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .block();
        return objectMapper.readTree(body == null ? "{}" : body);
    }

    private JsonNode getNasdaqJson(URI uri) throws Exception {
        String body = webClient.get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Origin", "https://www.nasdaq.com")
                .header("Referer", "https://www.nasdaq.com/")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .block();
        return objectMapper.readTree(body == null ? "{}" : body);
    }

    private void validateJuhe(JsonNode root) {
        if (root.has("error_code") && root.path("error_code").asInt(-1) != 0) {
            throw new IllegalStateException(root.path("reason").asText("juhe rejected request"));
        }
        if (root.has("resultcode") && !"200".equals(root.path("resultcode").asText())) {
            throw new IllegalStateException(root.path("reason").asText("juhe rejected request"));
        }
    }

    private JsonNode extractQuoteData(JsonNode root) {
        JsonNode result = root.path("result");
        if (result.isArray() && !result.isEmpty()) {
            return result.get(0).has("data") ? result.get(0).path("data") : result.get(0);
        }
        if (result.isObject() && result.has("data")) {
            return result.path("data");
        }
        return result;
    }

    private String secId(StockSymbolResolver.StockTarget target) {
        String gid = target.gid().toLowerCase(Locale.ROOT);
        return switch (target.market()) {
            case "hs" -> gid.startsWith("sh") ? "1." + gid.substring(2) : "0." + gid.substring(2);
            case "hk" -> "116." + gid.substring(2);
            case "us" -> usSecId(gid);
            default -> throw new IllegalArgumentException("unsupported stock market: " + target.market());
        };
    }

    private String usSecId(String gid) {
        String ticker = gid.toUpperCase(Locale.ROOT);
        if (NYSE_TICKERS.contains(ticker)) {
            return "106." + ticker;
        }
        if (AMEX_TICKERS.contains(ticker)) {
            return "107." + ticker;
        }
        return "105." + ticker;
    }

    private String currency(String market) {
        return currencyFor(market);
    }

    private static String currencyFor(String market) {
        return switch (market) {
            case "hk" -> "HKD";
            case "us" -> "USD";
            default -> "CNY";
        };
    }

    private String text(JsonNode node, String... keys) {
        return textOr(node, "", keys);
    }

    private String textOr(JsonNode node, String fallback, String... keys) {
        for (String key : keys) {
            String value = node.path(key).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
