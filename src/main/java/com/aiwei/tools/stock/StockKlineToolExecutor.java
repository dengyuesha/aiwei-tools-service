/*
 * 2026-08-03 Codex 修改：K线周期支持按月换算为交易日，避免“最近3个月”只返回最少10条。
 */
package com.aiwei.tools.stock;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 股票日 K 线查询执行器。
 */
@Component
public class StockKlineToolExecutor implements ToolExecutor {

    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private final StockSymbolResolver symbolResolver;
    private final StockMarketClient marketClient;

    /**
     * 创建 K 线执行器。
     *
     * @param symbolResolver 股票代码解析器
     * @param marketClient 行情客户端
     */
    public StockKlineToolExecutor(
            StockSymbolResolver symbolResolver,
            StockMarketClient marketClient) {
        this.symbolResolver = symbolResolver;
        this.marketClient = marketClient;
    }

    /**
     * 返回稳定逻辑工具名。
     *
     * @return stock.kline
     */
    @Override
    public String toolName() {
        return "stock.kline";
    }

    /**
     * 查询股票日 K 线。
     *
     * @param request 标准工具请求
     * @return OHLCV 结构化数据
     */
    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        Map<String, Object> arguments = request.arguments();
        String symbol = first(arguments, "symbol", "code", "name");
        String period = first(arguments, "period");
        if (period.isBlank()) {
            period = "30d";
        }
        String interval = first(arguments, "interval");
        if (interval.isBlank()) {
            interval = "1d";
        }
        if (!List.of("1d", "day", "daily", "日K", "日线").contains(interval)) {
            throw new IllegalArgumentException("only daily kline interval is supported");
        }
        StockSymbolResolver.StockTarget target = symbolResolver.resolve(symbol);
        List<Map<String, Object>> bars = marketClient.fetchDailyBars(target, barLimit(period));
        if (bars.isEmpty()) {
            throw new ToolExecutionException(
                    "NO_DATA",
                    "market provider returned no kline bars",
                    true,
                    "暂时没有查到这只股票的 K 线数据。");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symbol", target.gid());
        data.put("name", target.displayName());
        data.put("market", target.market());
        data.put("period", period);
        data.put("interval", "1d");
        data.put("count", bars.size());
        data.put("bars", bars);
        data.put("closes", bars.stream().map(bar -> bar.get("close")).toList());
        String summary = target.displayName() + "（" + target.gid() + "）近 "
                + bars.size() + " 个交易日 K 线已生成。";
        return new ToolExecutionResult("eastmoney_kline", summary, data, false);
    }

    private int barLimit(String period) {
        Matcher matcher = NUMBER.matcher(period);
        if (!matcher.find()) {
            return 30;
        }
        int value = Integer.parseInt(matcher.group(1));
        if (period.contains("y") || period.contains("年")) {
            return Math.min(value * 250, 300);
        }
        if (period.contains("w") || period.contains("周")) {
            return Math.min(value * 5, 120);
        }
        if (period.contains("m") || period.contains("月")) {
            return Math.min(value * 21, 300);
        }
        return Math.max(10, Math.min(value, 300));
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
