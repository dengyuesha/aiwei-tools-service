package com.aiwei.tools.stock;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 股票行情查询执行器。
 */
@Component
public class StockQuoteToolExecutor implements ToolExecutor {

    private static final Pattern WEB_URL = Pattern.compile(
            "https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOCK_CUE = Pattern.compile(
            "股票|股价|行情|涨跌|市值|K线|走势|stock|quote",
            Pattern.CASE_INSENSITIVE);

    private final StockSymbolResolver symbolResolver;
    private final StockMarketClient marketClient;

    /**
     * 创建股票行情执行器。
     *
     * @param symbolResolver 股票代码解析器
     * @param marketClient 行情客户端
     */
    public StockQuoteToolExecutor(
            StockSymbolResolver symbolResolver,
            StockMarketClient marketClient) {
        this.symbolResolver = symbolResolver;
        this.marketClient = marketClient;
    }

    /**
     * 返回稳定逻辑工具名。
     *
     * @return stock.quote
     */
    @Override
    public String toolName() {
        return "stock.quote";
    }

    /**
     * 查询实时行情；实时源不可用时使用最近交易日 K 线收盘价降级。
     *
     * @param request 标准工具请求
     * @return 标准行情结果
     */
    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        rejectMisroutedWebRequest(request.arguments());
        String rawSymbol = first(request.arguments(), "symbol", "code", "name");
        StockSymbolResolver.StockTarget target = symbolResolver.resolve(rawSymbol);
        if (List.of("hs", "hk").contains(target.market())) {
            try {
                Map<String, Object> quote = marketClient.fetchTencentQuote(target);
                return result("tencent_quote", quote, false, request.arguments());
            } catch (ToolExecutionException ignored) {
                // Keep the configured Juhe provider and delayed K-line close as fallbacks.
            }
        }
        try {
            Map<String, Object> quote = marketClient.fetchQuote(target);
            return result("juhe_stock", quote, false, request.arguments());
        } catch (ToolExecutionException quoteError) {
            List<Map<String, Object>> bars;
            try {
                bars = marketClient.fetchDailyBars(target, 2);
            } catch (ToolExecutionException barsError) {
                throw quoteError;
            }
            if (bars.isEmpty()) {
                throw quoteError;
            }
            Map<String, Object> latest = bars.get(bars.size() - 1);
            Map<String, Object> previous = bars.size() > 1 ? bars.get(bars.size() - 2) : latest;
            double close = number(latest.get("close"));
            double previousClose = number(previous.get("close"));
            double changeAmount = close - previousClose;
            double changePercent = previousClose == 0 ? 0 : changeAmount / previousClose * 100;
            Map<String, Object> quote = new LinkedHashMap<>();
            quote.put("symbol", target.gid());
            quote.put("name", target.displayName());
            quote.put("price", format(close, target.market()));
            quote.put("open", latest.getOrDefault("open", ""));
            quote.put("high", latest.getOrDefault("high", ""));
            quote.put("low", latest.getOrDefault("low", ""));
            quote.put("prev_close", format(previousClose, target.market()));
            quote.put("change_percent", String.format(Locale.ROOT, "%+.2f", changePercent));
            quote.put("change_amount", String.format(Locale.ROOT, "%+.3f", changeAmount));
            quote.put("volume", latest.getOrDefault("volume", ""));
            quote.put("currency", currency(target.market()));
            quote.put("market", target.market());
            quote.put("as_of", latest.getOrDefault("date", ""));
            quote.put("delayed", true);
            return result("eastmoney_kline", quote, true, request.arguments());
        }
    }

    private void rejectMisroutedWebRequest(Map<String, Object> arguments) {
        String text = first(arguments, "text", "query");
        if (!text.isBlank() && WEB_URL.matcher(text).find() && !STOCK_CUE.matcher(text).find()) {
            throw new ToolExecutionException(
                    "MISROUTED_WEB_REQUEST",
                    "stock.quote received a URL-reading request without stock intent",
                    false,
                    "这是网页读取请求，不应调用股票行情工具。");
        }
    }

    private ToolExecutionResult result(
            String provider,
            Map<String, Object> quote,
            boolean delayed,
            Map<String, Object> arguments) {
        String name = String.valueOf(quote.getOrDefault("name", quote.getOrDefault("symbol", "")));
        String symbol = String.valueOf(quote.getOrDefault("symbol", ""));
        String price = String.valueOf(quote.getOrDefault("price", "--"));
        String percent = String.valueOf(quote.getOrDefault("change_percent", ""));
        String amount = String.valueOf(quote.getOrDefault("change_amount", ""));
        String change = formatChange(percent, amount);
        String summary = name + "（" + symbol + "）"
                + (delayed ? "最近交易日收盘价 " : "最新价 ")
                + price
                + (change.isBlank() ? "" : "，涨跌 " + change)
                + "。";
        if (truthy(arguments.get("advice"))) {
            summary += "这些只是行情信息，不构成投资建议。";
        }
        Map<String, Object> data = new LinkedHashMap<>(quote);
        data.put("delayed", delayed);
        return new ToolExecutionResult(provider, summary, data, false);
    }

    private String formatChange(String percent, String amount) {
        String pct = percent == null ? "" : percent.trim().replace("%", "");
        String amt = amount == null ? "" : amount.trim();
        if (pct.isBlank() && amt.isBlank()) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        if (!pct.isBlank()) {
            if (!pct.startsWith("-") && !pct.startsWith("+")) {
                value.append("+");
            }
            value.append(pct).append("%");
        }
        if (!amt.isBlank()) {
            value.append("（");
            if (!amt.startsWith("-") && !amt.startsWith("+")) {
                value.append("+");
            }
            value.append(amt).append("）");
        }
        return value.toString();
    }

    private double number(Object value) {
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid stock price: " + value);
        }
    }

    private String format(double value, String market) {
        return String.format(Locale.ROOT, "hk".equals(market) ? "%.3f" : "%.2f", value);
    }

    private String currency(String market) {
        return switch (market) {
            case "hk" -> "HKD";
            case "us" -> "USD";
            default -> "CNY";
        };
    }

    private boolean truthy(Object value) {
        return value != null && List.of("true", "1", "yes", "是")
                .contains(String.valueOf(value).trim().toLowerCase(Locale.ROOT));
    }

    private String first(Map<String, Object> arguments, String... keys) {
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }
}
